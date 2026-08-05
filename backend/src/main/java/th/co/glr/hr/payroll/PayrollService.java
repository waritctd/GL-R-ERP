package th.co.glr.hr.payroll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationKind;
import th.co.glr.hr.payroll.obligation.DeductionObligationService;
import th.co.glr.hr.payroll.export.KBankPctExporter;
import th.co.glr.hr.payroll.export.PayrollDetailExporter;
import th.co.glr.hr.payroll.export.PayrollDetailIdentityDto;
import th.co.glr.hr.payroll.export.PayrollExportFile;
import th.co.glr.hr.payroll.export.PayrollExportKind;
import th.co.glr.hr.payroll.export.PayrollExportRow;
import th.co.glr.hr.payroll.export.Pnd1Exporter;
import th.co.glr.hr.payroll.export.SsoExporter;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.TaxAllowanceBulkUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.TaxAllowanceListResponse;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedBulkUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedListResponse;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest;

@Service
public class PayrollService {
    private static final Set<String> PAYROLL_VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> PAYROLL_EDIT_ROLES = Set.of("hr");
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");
    // Finding 4 fix (Opus review, 2026-07-30): calendar-plausible ceiling for a daily-rate employee's
    // HR-typed days-worked figure -- see #calculateLine's own comment on where this is enforced and
    // why, and V103's chk_payroll_line_days_worked_range for the matching DB-level backstop.
    private static final BigDecimal MAX_DAYS_WORKED = new BigDecimal("31");

    private final PayrollRepository payrollRepository;
    private final PayrollCalculator payrollCalculator;
    private final CommissionService commissionService;
    private final AuditService auditService;
    private final PayslipRenderer payslipRenderer;
    // Leave -> payroll dependency on the leave package. #suggestedInputs (2026-07-23) uses it
    // read-only to overlay leave-derived unpaidLeaveDays/pendingUnpaidLeaveCorrectionDays.
    // #preview/#process (2026-07-23, AUTO-REFUND) additionally read + (on process only) resolve
    // hr.leave_payroll_correction rows to auto-apply the cancel-after-close refund -- see
    // #calculateLine and #process below.
    private final LeaveRepository leaveRepository;
    private final KBankPctExporter kbankExporter;
    private final Pnd1Exporter pnd1Exporter;
    private final SsoExporter ssoExporter;
    private final PayrollDetailExporter payrollDetailExporter;
    private final AppProperties appProperties;
    // Deduction obligation tracking (issue #373): resolves the obligation-derived monthly amount
    // during #calculateLine, and records the remittance ledger during #process. See that class's
    // own javadoc for the full split between its read-only and side-effecting hooks.
    private final DeductionObligationService deductionObligationService;

    public PayrollService(
        PayrollRepository payrollRepository,
        PayrollCalculator payrollCalculator,
        CommissionService commissionService,
        AuditService auditService,
        PayslipRenderer payslipRenderer,
        LeaveRepository leaveRepository,
        KBankPctExporter kbankExporter,
        Pnd1Exporter pnd1Exporter,
        SsoExporter ssoExporter,
        PayrollDetailExporter payrollDetailExporter,
        AppProperties appProperties,
        DeductionObligationService deductionObligationService
    ) {
        this.payrollRepository = payrollRepository;
        this.payrollCalculator = payrollCalculator;
        this.commissionService = commissionService;
        this.auditService = auditService;
        this.payslipRenderer = payslipRenderer;
        this.leaveRepository = leaveRepository;
        this.kbankExporter = kbankExporter;
        this.pnd1Exporter = pnd1Exporter;
        this.ssoExporter = ssoExporter;
        this.payrollDetailExporter = payrollDetailExporter;
        this.appProperties = appProperties;
        this.deductionObligationService = deductionObligationService;
    }

    public PayrollPeriodDto currentOrPreview(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);
        return payrollRepository.findPeriodByMonth(month)
            .map(period -> {
                auditPayrollAccess("VIEW_PAYROLL_PERIOD", actor, period,
                    "base_salary,gross_earnings,deductions,net_pay,bank_account");
                return period;
            })
            .orElseGet(() -> preview(month, List.of(), actor));
    }

    /**
     * Special-pay carry-forward (2026-07-23): read-only suggestions to pre-fill a brand-new monthly
     * payroll run from each employee's most-recent PRIOR processed {@code payroll_line}. Does NOT feed
     * {@link #preview(ProcessPayrollRequest, UserPrincipal)} or {@link #process}, which keep taking
     * explicit inputs exactly as before — the frontend reads this endpoint separately and pre-fills
     * form fields HR can still edit/override. There is no "omitted means carry" ambiguity to resolve
     * here: the carry-forward step happens entirely client-side, before HR submits, and whatever value
     * is in the field when HR hits Preview/Process — carried, edited, or explicitly cleared to 0 — is
     * what goes into {@code inputs} and gets calculated/stored, unchanged from today's behaviour.
     *
     * <p>Leave -&gt; payroll unpaid-day deduction (2026-07-23): also overlays, per employee, this
     * month's leave-derived {@code unpaidLeaveDays} ({@link
     * LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) and any unresolved
     * cancel-after-close {@code pendingUnpaidLeaveCorrectionDays} ({@link
     * LeaveRepository#findPendingPayrollCorrectionsByEmployee}). Purely additive to the special-pay
     * carry-forward rows above: an employee with leave-derived figures but no prior processed
     * payroll_line still gets a row ({@link PayrollCarryForwardDtos.SuggestedInputRow#empty}). Like
     * the rest of this method, this NEVER feeds {@code preview()}/{@code process()} directly -- the
     * frontend pre-fills the unpaidLeaveDays form field from it, and HR can still override.
     *
     * <p>Leave requires approval (2026-08-05): also surfaces {@code pendingSubmittedLeaveCount}
     * ({@link LeaveRepository#countSubmittedLeaveOverlappingMonth}), a month-level (not
     * per-employee) advisory count of leave still awaiting approval for this month -- see {@link
     * PayrollCarryForwardDtos}'s own Javadoc paragraph for why this exists. Read-only, additive, same
     * "never feeds preview()/process()" guarantee as the rest of this method.
     */
    public PayrollCarryForwardDtos.SuggestedInputsResponse suggestedInputs(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);

        Map<Long, PayrollCarryForwardDtos.SuggestedInputRow> byEmployee = new LinkedHashMap<>();
        payrollRepository.findCarryForwardSuggestions(month)
            .forEach(row -> byEmployee.put(row.employeeId(), row));

        Map<Long, BigDecimal> unpaidLeaveDaysByEmployee = leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(month);
        Map<Long, BigDecimal> pendingCorrectionsByEmployee = leaveRepository.findPendingPayrollCorrectionsByEmployee();
        int pendingSubmittedLeaveCount = leaveRepository.countSubmittedLeaveOverlappingMonth(month);

        Set<Long> employeeIds = new LinkedHashSet<>(byEmployee.keySet());
        employeeIds.addAll(unpaidLeaveDaysByEmployee.keySet());
        employeeIds.addAll(pendingCorrectionsByEmployee.keySet());

        List<PayrollCarryForwardDtos.SuggestedInputRow> merged = employeeIds.stream()
            .map(employeeId -> {
                PayrollCarryForwardDtos.SuggestedInputRow base = byEmployee.getOrDefault(
                    employeeId, PayrollCarryForwardDtos.SuggestedInputRow.empty(employeeId));
                return new PayrollCarryForwardDtos.SuggestedInputRow(
                    employeeId,
                    base.specialPay1(), base.specialPay2(), base.specialPay3(), base.specialPay4(), base.specialPay5(),
                    base.specialPay6(), base.specialPay7(), base.specialPay8(), base.specialPay9(),
                    base.mealAllowance(),
                    base.nonTaxableIncome(), base.studentLoanDeduction(), base.legalExecutionDeduction(),
                    unpaidLeaveDaysByEmployee.getOrDefault(employeeId, BigDecimal.ZERO),
                    pendingCorrectionsByEmployee.getOrDefault(employeeId, BigDecimal.ZERO),
                    // Carry the per-run withholding override typed last run (nullable; the leave overlay
                    // above never touches it).
                    base.withholdingTaxOverride()
                );
            })
            .toList();

        return new PayrollCarryForwardDtos.SuggestedInputsResponse(month, merged, pendingSubmittedLeaveCount);
    }

    public PayrollPeriodDto preview(ProcessPayrollRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return preview(normalizeMonth(request.payrollMonth()), safeInputs(request.inputs()), actor);
    }

    @Transactional
    public PayrollPeriodDto process(ProcessPayrollRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        LocalDate month = normalizeMonth(request.payrollMonth());
        PayrollPeriodDto preview = preview(month, safeInputs(request.inputs()), actor);
        requireEveryDailyRateEmployeeHasDaysWorked(preview.lines());
        long periodId = payrollRepository.saveProcessedPeriod(month, actor.employeeId(), preview.lines());
        // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): the lines just saved already have
        // the refund baked in (computed by #preview above, in this same transaction). Now mark the
        // hr.leave_payroll_correction rows that refund came from as resolved by THIS period -- see
        // LeaveRepository#resolvePendingCorrections for exactly how that stays consistent with the
        // read (same WHERE-shape, same transaction) and idempotent across re-processing this month.
        leaveRepository.resolvePendingCorrections(periodId);
        // V128: attribute each approved welfare claim to the period that actually paid it.
        // hr.special_money_request.included_period_id has existed since V66 and was never written,
        // because nothing consumed these rows. Only fills NULLs, so re-processing this month does
        // not re-point a claim that an earlier run already attributed -- the same idempotency
        // guarantee saveProcessedPeriod's delete-then-insert gives hr.payroll_line. Never called
        // from #preview, which must stay side-effect-free.
        payrollRepository.markWelfareIncludedInPeriod(month, periodId);
        // Deduction obligation tracking (issue #373): record what was ACTUALLY deducted this period
        // (post every other cap) against each employee's driving obligation, then re-derive
        // ACTIVE/COMPLETED from the recomputed cumulative total. Idempotent per (obligation,
        // payrollMonth) -- see DeductionObligationRepository#upsertRemittance -- so reprocessing
        // this same month self-corrects instead of double-counting, the same guarantee
        // saveProcessedPeriod's own delete-then-insert already gives hr.payroll_line. Deliberately
        // NEVER called from #preview, which must stay side-effect-free.
        deductionObligationService.recordRemittances(month, periodId, preview.lines());
        // Deduction shortfall tracking (issue #376, generalising #373's requested/actual/shortfall
        // triple): the ONLY currently-supported deduction that can come out lower than what was
        // requested is LEGAL_EXECUTION_GARNISHMENT, via the ALREADY-enforced ป.วิ.พ. ม.302 cap (see
        // DeductionObligationService#recordGarnishmentShortfalls's own javadoc for why every other
        // deduction never has anything to record). Pure record-keeping -- changes no persisted
        // amount, reads figures that are already computed above.
        //
        // D-376-1 fix (Opus review): "requested" comes from preview.lines() itself
        // (PayrollLineDto#legalExecutionRequested), NEVER by calling #resolveMonthlyAmount again here.
        // A second call after #recordRemittances (above) has already possibly flipped an obligation's
        // ACTIVE/COMPLETED status is unsafe -- #findDrivingObligation's ORDER BY (status='ACTIVE') DESC
        // can then resolve a DIFFERENT obligation than the one #calculateLine actually used, fabricating
        // a "requested" figure the court order never asked for (reviewer's probe: two LEGAL_EXECUTION
        // obligations, one completing this very run, produced a ฿4,900 phantom shortfall against a
        // ฿100 court order that was satisfied in full). Reading it off the line instead makes that
        // re-derivation impossible by construction.
        deductionObligationService.recordGarnishmentShortfalls(month, periodId, preview.lines());
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Payroll period was not saved"));
        // Payroll input draft (2026-07-30): this month's real values are now committed to
        // hr.payroll_line, so any leftover hr.payroll_input_draft rows for it are superseded --
        // clear them in the same transaction so a stale draft can never resurrect over the
        // processed values on a later reload (see #getInputDraft's precedence rule).
        //
        // Optimistic concurrency (issue #422 follow-up): process() is a second writer of the same
        // draft set saveInputDraft's lock protects, so it takes the same month-scoped advisory
        // lock before clearing -- otherwise a concurrent saveInputDraft could read-compare-write
        // between this delete and the transaction's commit, silently resurrecting a draft row for
        // a month that just got processed. The clear itself is unconditional either way (as
        // before this change): once processed, no If-Match token is honoured for the month again
        // -- the next saveInputDraft call sees an empty draft set (PayrollDraftETag.EMPTY) and any
        // pre-process token it holds is refused as stale, exactly like any other conflict.
        payrollRepository.lockInputDraftMonth(month);
        payrollRepository.deleteInputDrafts(month);
        auditPayrollAccess("PROCESS_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        auditService.record(actor, "PROCESS_PAYROLL", "payroll_period", periodId, null, period);
        return period;
    }

    /**
     * Payroll input draft (2026-07-30): read back whatever HR last saved for this month via
     * {@link #saveInputDraft}. Read-only, HR/CEO view like every other payroll GET -- never feeds
     * {@link #preview}/{@link #process} directly. The frontend applies it ONLY when hydrating a
     * brand-new run (a month with no processed/void period yet, {@code status == PREVIEW && id ==
     * null}) so a draft can never shadow real persisted values from an already-touched period --
     * see PayrollPage.jsx's {@code load()}/{@code applyPeriod} for that precedence rule.
     *
     * <p>Optimistic concurrency (issue #422 follow-up): also returns the month-level ETag ({@link
     * PayrollDraftETag}) the client must thread back as {@code If-Match} on the next {@link
     * #saveInputDraft}. A plain snapshot, not locked -- this is advisory information for the
     * client, not the enforcement point (the enforcement point is {@link #saveInputDraft}'s locked
     * compare-then-write) -- but it MUST still come from a single read, not two.
     *
     * <p><b>Opus review finding F2, corrected:</b> this used to call {@link
     * PayrollRepository#findInputDrafts} and {@link PayrollRepository#findInputDraftVersions} as
     * two separate, unsynchronized round trips. Under READ COMMITTED, each takes its own snapshot,
     * so a writer committing in the gap produces a hazard in the UNSAFE direction: the figures read
     * can reflect the OLD state while the version read reflects the NEW (legitimately current)
     * state, handing the caller old data paired with a token that is not stale -- a caller who
     * edits from that view and saves passes the {@code If-Match} compare cleanly and silently
     * clobbers the write that happened in the gap. Reading both from {@link
     * PayrollRepository#findInputDraftRows} in ONE statement makes that gap impossible.
     */
    public PayrollInputDraftDtos.PayrollInputDraftResponse getInputDraft(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);
        List<PayrollRepository.DraftRow> rows = payrollRepository.findInputDraftRows(month);
        List<PayrollEmployeeInputRequest> drafts = rows.stream().map(PayrollRepository.DraftRow::input).toList();
        String etag = PayrollDraftETag.compute(rows.stream().map(PayrollRepository.DraftRow::version).toList());
        return new PayrollInputDraftDtos.PayrollInputDraftResponse(month, drafts, etag);
    }

    /**
     * Payroll input draft (2026-07-30): HR-only, same edit gate as {@code process}/tax-allowances/
     * ytd-seed/component-tax-treatments. Persists the raw typed inputs as-is -- no calculation is
     * performed here, and this never touches {@code hr.payroll_line} or any payroll math. See
     * {@link #getInputDraft} for the read side and {@link #process} for how a draft is superseded
     * once the month is actually processed.
     *
     * <p>Optimistic concurrency (issue #422 follow-up): {@code ifMatch} is now REQUIRED -- a
     * deliberate API contract change, not a side effect (see the PR body). A missing header is
     * refused with 428 rather than silently allowed, because a headerless write is exactly the
     * hole this closes: two HR users on the same month previously overwrote each other with no
     * conflict signal at all. The lock-then-compare-then-write below is what makes the check real
     * rather than decorative -- {@link PayrollRepository#lockInputDraftMonth} is held for the rest
     * of this transaction, so a second concurrent call blocks until the first commits (or rolls
     * back) and then re-reads the version set THAT call actually left behind, not a stale one read
     * before the lock was acquired.
     */
    @Transactional
    public PayrollInputDraftDtos.PayrollInputDraftResponse saveInputDraft(
            ProcessPayrollRequest request, UserPrincipal actor, String ifMatch) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        if (ifMatch == null || ifMatch.isBlank()) {
            // Opus review finding NIT-8: this message surfaces verbatim to HR (the frontend's
            // catch does `error.message || fallback`), so it must read as a normal Thai UI message,
            // not leak an HTTP header name -- matches the frontend's own fallback wording (see
            // PayrollPage.jsx's saveDraft 428 branch) rather than naming "If-Match".
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
                "ไม่พบข้อมูลอ้างอิงสำหรับบันทึกร่างเงินเดือน กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง");
        }
        LocalDate month = normalizeMonth(request.payrollMonth());
        payrollRepository.lockInputDraftMonth(month);
        String currentETag = PayrollDraftETag.compute(payrollRepository.findInputDraftVersions(month));
        if (!currentETag.equals(normalizeETagToken(ifMatch))) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง");
        }
        payrollRepository.saveInputDrafts(month, safeInputs(request.inputs()), actor.employeeId());
        // One read for the response, same as #getInputDraft -- not a correctness requirement here
        // (the advisory lock already rules out a concurrent writer for the rest of this
        // transaction, so two separate reads would still agree), just consistent with the pattern
        // and one fewer round trip.
        List<PayrollRepository.DraftRow> rows = payrollRepository.findInputDraftRows(month);
        List<PayrollEmployeeInputRequest> drafts = rows.stream().map(PayrollRepository.DraftRow::input).toList();
        String newETag = PayrollDraftETag.compute(rows.stream().map(PayrollRepository.DraftRow::version).toList());
        return new PayrollInputDraftDtos.PayrollInputDraftResponse(month, drafts, newETag);
    }

    /**
     * Accepts the token exactly as {@link #getInputDraft} returned it in the response body (the
     * shape the frontend actually threads through), but tolerates the RFC 7232 quoted-string form
     * (and a leading weak-validator {@code W/}) too, since that is what the {@code ETag} response
     * header carries and a caller copying that header value verbatim should not be punished for
     * following the HTTP-correct form instead of the body field.
     *
     * <p><b>Known divergence from RFC 9110 §13.1.1 (Opus review finding F12/NEW-2), recorded
     * rather than fixed</b>: a real {@code If-Match} header can be the wildcard {@code *} (meaning
     * "any current representation is fine") or a comma-separated list of multiple entity-tags. This
     * method does neither: {@code *} is compared as the four-character literal string {@code "*"},
     * which will never equal a real SHA-256 hex token, so it always 409s instead of the RFC's
     * "succeed if any representation exists." A multi-valued header (either one physical {@code
     * If-Match} line with comma-separated tags, OR repeated {@code If-Match} headers -- Spring's
     * {@code @RequestHeader(String)} binding joins repeated headers of the same name with {@code
     * ", "} before this method ever sees the value) is likewise compared as one joined string
     * against the single current token and always fails to match, so it also always 409s. Both
     * failure modes are CLOSED (refuse a write that RFC semantics might have allowed), never OPEN
     * (never accept a write that should have been refused) -- this is documentation debt, not a
     * defect, and is acceptable because the only real caller is this repo's own frontend, which
     * never sends either form.
     */
    private String normalizeETagToken(String raw) {
        String value = raw.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Finding 1 fix (Opus review, 2026-07-30): a daily-rate employee HR never entered a day count
     * for -- either because no input row was submitted at all for them, or an explicitly typed 0 --
     * computes {@code SALARY = rate * 0 = ฿0} with no error anywhere: {@link #calculateLine} treats a
     * null/omitted {@code daysWorked} as "0 unless entered", the exact same convention every other
     * one-off field (bonusPay, otherOneOffPay, ...) already follows, and {@code
     * requireEveryNonZeroComponentClassified} never fires because the resulting amount genuinely is
     * zero. The run SUCCEEDS and produces a ฿0 payslip that nets to zero with nothing distinguishing
     * it from an intentional zero-pay employee.
     *
     * <p>Refused here -- at PROCESS, not at {@link #preview}/{@link #currentOrPreview} -- because a
     * blank/zero day count is the NORMAL, expected state of an in-progress run the moment HR opens a
     * new month and has not yet typed anything for anyone: {@code calculateLine} runs identically for
     * preview and process (both go through the same private method), so gating this inside it would
     * 409 the mere act of opening the payroll page before HR has entered a single figure. Gating it
     * here instead lets HR see the ฿0 line in Preview (exactly as before), edit it, and only blocks
     * the one action that actually commits a payslip.
     *
     * <p>Mirrors {@code PayrollCalculator#requireEveryNonZeroComponentClassified}'s existing pattern
     * (loop every line, throw a CONFLICT naming the employee on the first violation) rather than
     * inventing a new shape for this refusal.
     */
    private void requireEveryDailyRateEmployeeHasDaysWorked(List<PayrollLineDto> lines) {
        for (PayrollLineDto line : lines) {
            if ("D".equals(line.payType()) && (line.daysWorked() == null || line.daysWorked().signum() <= 0)) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "พนักงาน " + line.employeeCode() + " " + line.employeeName()
                        + " เป็นพนักงานรายวันแต่ยังไม่ได้ระบุจำนวนวันทำงานในงวดนี้"
                        + " กรุณากรอกจำนวนวันทำงานก่อนประมวลผลเงินเดือน มิฉะนั้นพนักงานจะได้รับเงินเดือน ฿0");
            }
        }
    }

    /**
     * Generate one of HR's payroll export files for a period: the three statutory CP874 text files
     * (KBank PCT, PND1, SSO สปส.1-10), or the {@link PayrollExportKind#PAYROLL_DETAIL} xlsx
     * workbook. HR/CEO only; reads PDPA-restricted PII for PND1/SSO (and, for the detail workbook,
     * bank account + every payroll_line figure), so every call is audited with the specific fields
     * exposed. {@code effectiveDate} is the HR-picked transfer/pay date (or, for the detail
     * workbook, just the date stamped into its filename); null falls back to the configured default
     * day (the 26th) of the payroll month.
     *
     * <p>No status gate on {@code period.status()} for {@code KBANK}/{@code PND1}/{@code SSO}: a
     * {@code VOID}ed period still has a real {@code periodId} and exports whatever remains in
     * {@code hr.payroll_line} for it, same as those three kinds have always done ({@code
     * findExportRows} has never special-cased VOID). {@link PayrollExportKind#PAYROLL_DETAIL} is
     * different (owner requirement, 2026-07-30): for a genuinely {@code PROCESSED} period it reads
     * the persisted lines verbatim (they ARE the authoritative record of what was actually paid),
     * but for anything else — most concretely a {@code VOID} period whose {@code hr.payroll_line}
     * rows hold HR's typed inputs behind stale/zero computed figures (a real, current example: 2026-07,
     * {@code period_id=1}, {@code status='VOID'}) — this method reconstructs those inputs (see
     * {@link #reconstructInputRequest}) and re-runs them through the SAME live {@link #preview}
     * path a fresh preview uses, so the workbook always reflects a real calculation rather than
     * whatever happens to be sitting in the row. See {@link #exportDetailPreview} for the case with
     * no persisted period at all.
     */
    public PayrollExportFile export(PayrollExportKind kind, long periodId, LocalDate effectiveDate, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงวดเงินเดือนนี้"));
        LocalDate payrollMonth = period.payrollMonth();
        LocalDate payDate = resolveEffectiveDate(effectiveDate, payrollMonth);
        AppProperties.Employer employer = appProperties.getPayroll().getEmployer();

        byte[] content;
        String auditFields;
        switch (kind) {
            case KBANK -> {
                content = kbankExporter.export(payrollRepository.findExportRows(periodId), employer, payDate);
                auditFields = "bank_account,net_pay";
            }
            case PND1 -> {
                content = pnd1Exporter.export(payrollRepository.findExportRows(periodId), employer, payrollMonth, payDate);
                auditFields = "national_id,tax_id,gross_taxable_income,withholding_tax";
            }
            case SSO -> {
                content = ssoExporter.export(payrollRepository.findExportRows(periodId), employer, payrollMonth, payDate);
                auditFields = "social_security_no,sso_wage_base,social_security";
            }
            case PAYROLL_DETAIL -> {
                PayrollPeriodDto detailPeriod;
                if ("PROCESSED".equals(period.status())) {
                    detailPeriod = period;
                } else {
                    List<PayrollEmployeeInputRequest> reconstructed = period.lines().stream()
                        .map(this::reconstructInputRequest)
                        .toList();
                    detailPeriod = preview(payrollMonth, reconstructed, actor);
                }
                content = buildDetailContent(detailPeriod);
                auditFields = DETAIL_EXPORT_AUDIT_FIELDS;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทไฟล์ส่งออกนี้");
        }
        auditPayrollAccess("EXPORT_PAYROLL_" + kind.name(), actor, period, auditFields);
        return new PayrollExportFile(kind, kind.fileName(payDate), content);
    }

    /**
     * The detailed payroll xlsx for a month that has never been (or is not yet) processed at all --
     * owner requirement, 2026-07-30: "July 2026 is live and still unprocessed... HR's whole reason
     * to want this file is to review the month BEFORE committing it." There is no {@code periodId}
     * to key off in this case ({@link #preview} never persists one), so the caller must submit the
     * same {@code payrollMonth}/{@code inputs} payload {@code POST /api/payroll/preview} already
     * takes. Reuses that exact private {@link #preview} computation -- the one the on-screen
     * "Preview"/"Refresh" buttons call -- so the workbook's figures are, by construction, identical
     * to whatever the screen shows for the same inputs; there is no second calculation path for the
     * two to silently disagree over.
     */
    public PayrollExportFile exportDetailPreview(ProcessPayrollRequest request, LocalDate effectiveDate, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(request.payrollMonth());
        PayrollPeriodDto livePeriod = preview(month, safeInputs(request.inputs()), actor);
        LocalDate payDate = resolveEffectiveDate(effectiveDate, month);
        byte[] content = buildDetailContent(livePeriod);
        auditPayrollAccess("EXPORT_PAYROLL_PAYROLL_DETAIL_PREVIEW", actor, livePeriod, DETAIL_EXPORT_AUDIT_FIELDS);
        return new PayrollExportFile(PayrollExportKind.PAYROLL_DETAIL,
            PayrollExportKind.PAYROLL_DETAIL.fileName(payDate), content);
    }

    private static final String DETAIL_EXPORT_AUDIT_FIELDS =
        "employee_name,department_name,bank_account,base_salary,special_pays,gross_earnings,deductions,net_pay";

    /**
     * Builds the detail workbook from a fully-computed {@code period} (its {@code lines()} plus its
     * own {@code periodStart}/{@code periodEnd}, which {@link PayrollDetailExporter} needs for the
     * "เงินเดือนใหม่ this month" rule) -- NOT just a bare line list, so every call site (processed,
     * recomputed-from-VOID, and pure preview) reports the correct period window.
     */
    private byte[] buildDetailContent(PayrollPeriodDto period) {
        List<PayrollLineDto> lines = period.lines();
        Set<Long> employeeIds = lines.stream().map(PayrollLineDto::employeeId).collect(Collectors.toSet());
        Map<Long, PayrollDetailIdentityDto> identities = payrollRepository.findDetailIdentity(employeeIds);
        return payrollDetailExporter.export(lines, identities, period);
    }

    /**
     * Reverses a persisted (possibly stale) {@link PayrollLineDto} back into the input shape {@link
     * #preview}/{@link #process} take, so a VOID period's already-typed HR inputs (special pays,
     * meal/per-diem, days worked, ...) survive a fresh recompute instead of being discarded. The 16
     * tax-allowance fields (spouse/child/parent-care/...) and {@code parentCareCount} are NOT
     * reconstructable from a payroll_line row (only their already-summed {@code
     * taxAllowanceTotal} is persisted, not the per-type breakdown) -- left {@code null}, which is
     * "no per-run override", so the recompute falls back to the EMPLOYEE'S CURRENT standing
     * tax-allowance declaration. That is the more correct answer, not a lesser one: it reflects
     * whatever HR has on file today rather than replaying a historical override that may since have
     * been corrected. {@code customerReturnDeduction} is read from {@code
     * customerReturnRequested} (what HR actually typed) rather than {@code
     * customerReturnDeduction} (the post-tax bookkeeping figure, which is 0 in the unearned path --
     * see that field's own javadoc on {@link PayrollLineDto}).
     */
    private PayrollEmployeeInputRequest reconstructInputRequest(PayrollLineDto line) {
        return new PayrollEmployeeInputRequest(
            line.employeeId(),
            specialPayAt(line, 0), specialPayAt(line, 1), specialPayAt(line, 2), specialPayAt(line, 3),
            specialPayAt(line, 4), specialPayAt(line, 5), specialPayAt(line, 6), specialPayAt(line, 7),
            specialPayAt(line, 8),
            line.nonTaxableIncome(),
            line.unpaidLeaveDays(),
            line.studentLoanDeduction(),
            line.legalExecutionDeduction(),
            line.otherPostTaxDeductions(),
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            line.warningLetterDeduction(),
            line.customerReturnRequested(),
            line.otherPretaxDeduction(),
            line.withholdingTaxOverride(),
            line.mealAllowance(),
            line.perDiemExempt(),
            line.perDiemTaxable(),
            parseEnum(PerDiemBasis.class, line.perDiemBasis()),
            line.bonusPay(),
            line.otherOneOffPay(),
            line.customerReturnAlreadyEarned(),
            parseEnum(PayrollGarnishmentType.class, line.garnishmentType()),
            null,
            line.daysWorked()
        );
    }

    private BigDecimal specialPayAt(PayrollLineDto line, int index) {
        List<PayrollSpecialPayDto> pays = line.specialPays();
        if (pays == null || index >= pays.size()) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = pays.get(index).amount();
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** HR-picked date, else the configured default transfer day (26th) clamped to the month length. */
    private LocalDate resolveEffectiveDate(LocalDate effectiveDate, LocalDate payrollMonth) {
        if (effectiveDate != null) {
            return effectiveDate;
        }
        int day = Math.min(
            appProperties.getPayroll().getEmployer().getDefaultTransferDay(),
            payrollMonth.lengthOfMonth());
        return payrollMonth.withDayOfMonth(Math.max(day, 1));
    }

    /**
     * Bulk payslip ZIP (owner requirement, 2026-07-30): every payslip for a processed period in one
     * archive, so HR can review the whole batch BEFORE {@code POST /{periodId}/distribute} emails it
     * to every employee -- today the only way to check is one-by-one, or after the emails are
     * already out. Reuses {@link PayslipRenderer#toPdf} per line -- the EXACT same call {@link
     * #payslipPdf} and the emailed attachment ({@link PayslipDistributionService}) already make --
     * so every PDF in the archive is byte-identical to what those two paths independently produce.
     * A second renderer here would let the reviewed artefact and the emailed one silently diverge,
     * which is the whole failure mode this endpoint exists to close.
     *
     * <p>HR/CEO only, the same {@link #PAYROLL_VIEW_ROLES} gate {@link #payslipPdf}/{@link #export}
     * already use. Requires a genuinely {@code PROCESSED} period -- unlike the detail xlsx export,
     * a payslip is a document about money actually paid, and generating 30 of them from an
     * uncommitted preview would let HR circulate paperwork for a run that never happened.
     */
    public byte[] bulkPayslipZip(long periodId, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงวดเงินเดือนนี้"));
        if (!"PROCESSED".equals(period.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "งวดนี้ยังไม่ได้ประมวลผล ไม่สามารถดาวน์โหลดสลิปเงินเดือนทั้งหมดได้");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Set<String> usedNames = new HashSet<>();
            for (PayrollLineDto line : period.lines()) {
                byte[] pdf = payslipRenderer.toPdf(line, period);
                zip.putNextEntry(new ZipEntry(payslipEntryName(line, usedNames)));
                zip.write(pdf);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        auditPayrollAccess("BULK_DOWNLOAD_PAYSLIPS", actor, period,
            "earnings,sso,tax,deductions,net_pay,bank_account");
        auditService.record(actor, "BULK_DOWNLOAD_PAYSLIPS", "payroll_period", periodId, null,
            Map.of("periodId", periodId, "employeeCount", period.lines().size()));
        return out.toByteArray();
    }

    /** {@code employeeCode}, never just a first name -- see this method's caller's javadoc. */
    private String payslipEntryName(PayrollLineDto line, Set<String> usedNames) {
        String code = (line.employeeCode() == null || line.employeeCode().isBlank())
            ? "emp" + line.employeeId()
            : line.employeeCode();
        String name = "glr-payslip-" + code + ".pdf";
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = "glr-payslip-" + code + "-" + (suffix++) + ".pdf";
        }
        return name;
    }

    public byte[] payslipPdf(long periodId, long lineId, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงวดเงินเดือนนี้"));
        PayrollLineDto line = period.lines().stream()
            .filter(item -> item.id() != null && item.id() == lineId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการเงินเดือนนี้"));
        byte[] pdf = payslipRenderer.toPdf(line, period);
        auditPayrollLineAccess("VIEW_PAYSLIP_PDF", actor, period, line,
            "earnings,sso,tax,deductions,net_pay,bank_account");
        auditService.record(actor, "VIEW_PAYSLIP_PDF", "payroll_line", line.id(), null, auditPayload(period, line));
        return pdf;
    }

    public byte[] ownPayslipPdf(long periodId, UserPrincipal actor) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงวดเงินเดือนนี้"));
        PayrollLineDto line = period.lines().stream()
            .filter(item -> item.employeeId() == actor.employeeId())
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสลิปเงินเดือนสำหรับงวดนี้"));
        byte[] pdf = payslipRenderer.toPdf(line, period);
        auditPayrollLineAccess("VIEW_OWN_PAYSLIP_PDF", actor, period, line,
            "earnings,sso,tax,deductions,net_pay,bank_account");
        auditService.record(actor, "VIEW_OWN_PAYSLIP_PDF", "payroll_line", line.id(), null, auditPayload(period, line));
        return pdf;
    }

    private static final String ALLOWANCE_ESTIMATE_DISCLAIMER =
        "ค่าประมาณการนี้คำนวณจากข้อมูลปัจจุบัน (ยอดสะสม โบนัส คอมมิชชั่น ณ วันที่ประมาณการ) "
            + "ตัวเลขจริงเมื่อประมวลผลเงินเดือนอาจเปลี่ยนแปลงได้";

    /**
     * Tax-allowance declaration plan, decision #4 ("estimated 'what this saves me'"). Runs the REAL
     * calculator TWICE for a single employee/month — baseline (the employee's current standing
     * declaration, exactly what {@link #preview} would resolve via {@code
     * findTaxAllowancesByEmployee}) vs proposed (the declaration under review, not yet applied) —
     * and reports the withholding-tax delta. Reuses the EXACT collaborators {@link #preview}
     * assembles for every other employee ({@code findActiveEmployees} filtered to one, {@code
     * findYearToDateByEmployee}, {@link #treatmentsFor}, {@link #ssoInclusionFor}, {@code
     * findApprovedOvertimePayByEmployee}, {@link #commissionPayByEmployee}) so there is no second
     * calculation path to silently disagree with a real run.
     *
     * <p>{@code employeeId} is supplied by the CALLER (the controller/service boundary resolves it
     * from {@code actor.employeeId()} — see {@code TaxAllowanceDeclarationService#estimateOwn} —
     * never from an HTTP parameter; there is no employeeId field on the estimate endpoint's request
     * body at all). This method still requires {@code actor} to be a real authenticated employee, the
     * same shape of guard {@link #ownPayslipPdf} uses, so it can never be called with no actor.
     *
     * <p>{@code input} is deliberately {@code null} for both calculations: an estimate has no
     * per-run HR-typed corrections to layer on, only the standing declaration (baseline) or the
     * declaration under review (proposed) — see {@link #mergeAllowances}, which returns its {@code
     * stored} argument completely unchanged when {@code input == null}. This is exactly what makes
     * the anti-drift pinning test hold: applying the proposed declaration and then previewing the
     * same month for real also calls {@code calculateLine} with {@code input == null} for an
     * employee nobody typed a per-run correction for, so the two paths merge allowances identically.
     *
     * <p>leaveRefundDays is fixed at zero here — deliberately narrower than {@link #preview}'s own
     * {@code leaveRepository.findRefundableUnpaidDaysByEmployee} lookup, which needs an
     * {@code existingPeriodId} an estimate has no reason to resolve. An estimate is advisory, not a
     * persisted figure, and a cancel-after-close leave refund is edge-case enough that omitting it
     * here (never silently reintroducing it wrong) is the safer choice.
     */
    public PayrollAllowanceEstimateResult estimateAllowanceEffect(
        long employeeId, int taxYear, int effectiveMonth,
        PayrollTaxAllowanceInput proposedAllowances, UserPrincipal actor
    ) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        LocalDate payrollMonth = LocalDate.of(taxYear, effectiveMonth, 1);

        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        PayrollEmployeeSnapshot employee = employees.stream()
            .filter(candidate -> candidate.employeeId() == employeeId)
            .findFirst()
            .orElse(null);
        if (employee == null) {
            return unavailableAllowanceEstimate(taxYear, effectiveMonth,
                "ไม่พบข้อมูลพนักงานที่พร้อมประมวลผลเงินเดือนสำหรับการประมาณการนี้");
        }
        // Failure mode (b): a daily-rate employee has no daysWorked outside a real run, so
        // grossSalaryComponent would compute to ฿0 (see #calculateLine's dailyRatePay branch) — not
        // a meaningful estimate. Reported honestly rather than showing a fabricated ฿0 saving.
        if (employee.dailyRatePay()) {
            return unavailableAllowanceEstimate(taxYear, effectiveMonth,
                "พนักงานรายวันยังไม่มีจำนวนวันทำงานของงวดนี้ ระบบจึงไม่สามารถประมาณการผลของค่าลดหย่อนต่อภาษีได้ล่วงหน้า "
                    + "ตัวเลขจะปรากฏเมื่อฝ่ายบุคคลประมวลผลเงินเดือนจริงและระบุจำนวนวันทำงานแล้ว");
        }

        Map<Long, BigDecimal> overtimeByEmployee = payrollRepository.findApprovedOvertimePayByEmployee(payrollMonth);
        Map<Long, BigDecimal> commissionByEmployee = commissionPayByEmployee(payrollMonth);
        Map<Long, PayrollYearToDate> yearToDateByEmployee = payrollRepository.findYearToDateByEmployee(payrollMonth);
        Map<Long, PayrollTaxAllowanceInput> storedAllowancesByEmployee =
            payrollRepository.findTaxAllowancesByEmployee(payrollMonth);
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsByEmployee =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear);
        Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee =
            payrollRepository.findComponentSsoInclusionByEmployee(taxYear);

        BigDecimal overtimePay = overtimeByEmployee.getOrDefault(employeeId, BigDecimal.ZERO);
        BigDecimal commissionPay = commissionByEmployee.getOrDefault(employeeId, BigDecimal.ZERO);
        PayrollYearToDate yearToDate = yearToDateByEmployee.getOrDefault(employeeId, PayrollYearToDate.empty());
        Map<PayrollComponent, PayrollTaxTreatment> treatments = treatmentsFor(employeeId, treatmentsByEmployee,
            hasBeenOnboardedForPayroll(employeeId, ssoInclusionByEmployee));
        Map<PayrollComponent, Boolean> ssoInclusion = ssoInclusionFor(employeeId, ssoInclusionByEmployee);
        PayrollTaxAllowanceInput baselineAllowances = storedAllowancesByEmployee.get(employeeId);

        try {
            // Welfare deliberately ZERO here: this is the tax-allowance what-if estimator, and both
            // sides of the comparison must differ ONLY by the allowance change. Feeding real welfare
            // in would move both figures by the same amount and change the delta the employee is
            // shown, for a reason that has nothing to do with their allowance declaration.
            BigDecimal welfarePay = BigDecimal.ZERO;
            PayrollLineDto baselineLine = calculateLine(employee, null, overtimePay, commissionPay, welfarePay,
                yearToDate, baselineAllowances, BigDecimal.ZERO, payrollMonth, treatments, ssoInclusion);
            PayrollLineDto proposedLine = calculateLine(employee, null, overtimePay, commissionPay, welfarePay,
                yearToDate, proposedAllowances, BigDecimal.ZERO, payrollMonth, treatments, ssoInclusion);
            return new PayrollAllowanceEstimateResult(
                true, null, taxYear, effectiveMonth,
                baselineLine.withholdingTax(), proposedLine.withholdingTax(),
                baselineLine.withholdingTax().subtract(proposedLine.withholdingTax()),
                baselineLine.taxAllowanceTotal(), proposedLine.taxAllowanceTotal(),
                ALLOWANCE_ESTIMATE_DISCLAIMER);
        } catch (ApiException e) {
            // Failure mode (a): calculateClassified 409s (CONFLICT) when a non-zero non-SALARY
            // component has no stored tax treatment anywhere for this employee/year. Caught here and
            // reported honestly -- never synthesize a treatment just to force an estimate out. Any
            // OTHER status (there is none today, but defensively) is a genuine error, not an
            // unavailability, and must propagate.
            if (e.getStatus() == HttpStatus.CONFLICT) {
                return unavailableAllowanceEstimate(taxYear, effectiveMonth,
                    "มีรายรับบางประเภทที่ยังไม่ได้กำหนดประเภทภาษีสำหรับพนักงานคนนี้ "
                        + "ฝ่ายบุคคลต้องกำหนดประเภทภาษีให้ครบก่อนจึงจะประมาณการภาษีได้ (" + e.getMessage() + ")");
            }
            throw e;
        }
    }

    private PayrollAllowanceEstimateResult unavailableAllowanceEstimate(int taxYear, int effectiveMonth, String reason) {
        return new PayrollAllowanceEstimateResult(
            false, reason, taxYear, effectiveMonth, null, null, null, null, null, null);
    }

    private PayrollPeriodDto preview(LocalDate payrollMonth, List<PayrollEmployeeInputRequest> inputs, UserPrincipal actor) {
        Map<Long, PayrollEmployeeInputRequest> inputByEmployee = inputs.stream()
            .collect(Collectors.toMap(PayrollEmployeeInputRequest::employeeId, Function.identity(), (left, right) -> right));
        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        Map<Long, BigDecimal> overtimeByEmployee = payrollRepository.findApprovedOvertimePayByEmployee(payrollMonth);
        Map<Long, BigDecimal> commissionByEmployee = commissionPayByEmployee(payrollMonth);
        // V128: approved สวัสดิการ now reaches payroll instead of being keyed by hand.
        Map<Long, BigDecimal> welfareByEmployee = payrollRepository.findApprovedWelfarePayByEmployee(payrollMonth);
        Map<Long, PayrollYearToDate> yearToDateByEmployee = payrollRepository.findYearToDateByEmployee(payrollMonth);
        // C1: the standing tax-allowance declaration for this payroll's tax year is the BASE. Any
        // field the request body supplies for an employee (non-null) is an in-run correction and wins
        // over the stored value -- stored = standing declaration, body = this-run override.
        Map<Long, PayrollTaxAllowanceInput> storedAllowancesByEmployee =
            payrollRepository.findTaxAllowancesByEmployee(payrollMonth);
        // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): existingPeriodId is null the first
        // time this month is previewed/processed, and non-null on a re-preview/re-process of a month
        // that already has a period row (PREVIEW-only rows from currentOrPreview's persistence path
        // do not exist -- only saveProcessedPeriod creates one -- so in practice this is non-null
        // exactly when this month has been PROCESSED before). Passing it into
        // findRefundableUnpaidDaysByEmployee gives back any correction that same period previously
        // resolved, so a re-run recomputes the correct total instead of losing or double-counting it
        // -- see that method's doc for the full idempotency argument, and PayrollService#process for
        // the paired resolve call.
        Long existingPeriodId = payrollRepository.findPeriodByMonth(payrollMonth).map(PayrollPeriodDto::id).orElse(null);
        Map<Long, BigDecimal> leaveRefundDaysByEmployee = leaveRepository.findRefundableUnpaidDaysByEmployee(existingPeriodId);

        // Task 2 (2026-07-29): per-employee, per-component tax treatment + SSO inclusion, replacing
        // the hardcoded single-limb split. See PayrollCalculator#calculateClassified and
        // docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md.
        int taxYear = payrollMonth.getYear();
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsByEmployee =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear);
        Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee =
            payrollRepository.findComponentSsoInclusionByEmployee(taxYear);

        List<PayrollLineDto> lines = employees.stream()
            .map(employee -> calculateLine(
                employee,
                inputByEmployee.get(employee.employeeId()),
                overtimeByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                commissionByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                welfareByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                yearToDateByEmployee.getOrDefault(employee.employeeId(), PayrollYearToDate.empty()),
                storedAllowancesByEmployee.get(employee.employeeId()),
                leaveRefundDaysByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                payrollMonth,
                // Genuine conflict (rebase, 2026-07-29), not a mechanical merge: branch 117's
                // remainingPayPeriods/taxpayerAge fed the OLD single-limb payrollCalculator.calculate()
                // path; branch 118 (task 2) repoints calculateLine onto calculateClassified exclusively
                // (see PayrollClassifiedCalculationDtos' class javadoc), which has no use for either --
                // it derives monthsRemaining from payrollMonthValue directly and does not apply the V93
                // elderly/disabled exemption (exemptIncome/assessAnnualTax are calculate()-only). Kept
                // as-is rather than threaded through calculateClassified/PayrollClassifiedCalculationInput,
                // which would be inventing behaviour fa69e4fa does not implement; flagged as a known gap
                // in docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md,
                // "Progress -- task 4: rebase report + F1-F7 fixes" section, known risks (F4 correction,
                // Opus review 2026-07-30: that section did not exist when this comment was written;
                // it now does).
                treatmentsFor(employee.employeeId(), treatmentsByEmployee,
                    hasBeenOnboardedForPayroll(employee.employeeId(), ssoInclusionByEmployee)),
                ssoInclusionFor(employee.employeeId(), ssoInclusionByEmployee)
            ))
            .sorted(Comparator.comparing(PayrollLineDto::employeeCode))
            .toList();
        PayrollPeriodDto period = new PayrollPeriodDto(
            null,
            payrollMonth,
            payrollMonth,
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            "PREVIEW",
            OffsetDateTime.now(),
            actor.employeeId(),
            lines.size(),
            sum(lines, PayrollLineDto::grossEarnings),
            sum(lines, PayrollLineDto::totalDeductions),
            sum(lines, PayrollLineDto::netPay),
            sum(lines, PayrollLineDto::socialSecurity),
            sum(lines, PayrollLineDto::withholdingTax),
            lines
        );
        auditPayrollAccess("PREVIEW_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        return period;
    }

    /**
     * P0 fix (Opus review, 2026-07-30): the signal {@link #treatmentsFor} needs to tell "an employee a
     * real onboarding path touched" apart from "an employee that merely has an incidental, unrelated
     * SSO row" -- see that method's own javadoc for the regression that made a cruder {@code
     * containsKey} check insufficient. A real onboarding path ({@code
     * PayrollRepository#seedSsoInclusionDefaults}, called from V96's backfill, {@code
     * EmployeeService#create}, and this task's own P1 fix) always writes a row for EVERY {@link
     * PayrollComponent} in one pass, so it always includes several components other than {@code
     * SALARY}. Checking for at least one non-SALARY component is therefore robust to exactly which
     * components got seeded (V96 predates {@code MEAL_ALLOWANCE}/{@code PER_DIEM_TAXABLE}, so an
     * employee it touched has 16 rows, not the current 18 -- an exact count would wrongly treat that
     * employee as "not onboarded") while still rejecting a single incidental SALARY-only row, which
     * several tests in this suite seed purely so their OWN unrelated {@code socialSecurity} assertion
     * comes out non-zero (see e.g. {@code PayrollClassifiedEngineIntegrationTest#seedEmployee}).
     *
     * <p><b>Finding 1 fix (fourth Opus review, 2026-07-30): the polarity of the "no SSO rows at all"
     * case was backwards.</b> This method used to {@code return false} the instant {@code
     * ssoInclusion == null} -- i.e. an employee with ZERO rows in BOTH matrices was treated as "not
     * onboarded" and got no synthesized default, 409ing the run. That is exactly backwards: V96 (the
     * SSO backfill) and V100 (the tax-treatment backfill) both {@code CROSS JOIN hr.employee} with no
     * {@code WHERE}, and {@code EmployeeService#create} seeds both matrices in the SAME transaction --
     * no real deployment path ever produces "SSO rows present, treatment rows absent" without ALSO
     * producing the reverse (both present) or "both absent" first. "Both absent" is the population
     * this safety net exists for in the first place: {@code db/migration-uat/V900} inserts 93
     * employees by raw {@code INSERT INTO hr.employee}, and on a freshly rebuilt uat Flyway runs V96/
     * V97/V100 (all merged-location, ordered by version number) BEFORE V900, reaching none of those
     * 93 rows -- {@code V902} then seeds approved overtime for them, so the payroll page's own initial
     * GET carries a non-zero component for every one of them with no HR input at all. The previous
     * logic refused exactly this population the fix's own stated goal was written to cover. Zero rows
     * in BOTH matrices is therefore unambiguously "never onboarded by any path" and now synthesizes,
     * same as the pre-existing non-SALARY-row case below (which stays exactly as it was, still
     * covering {@link PayrollClassificationReachabilityIntegrationTest}'s employee -- seeded through
     * {@code seedSsoInclusionDefaults} alone, so it has SSO rows but zero treatment rows). The one
     * state that must still NOT synthesize -- an SSO map that exists but carries only the incidental
     * SALARY-only convenience row several tests seed (see {@code
     * PayrollClassifiedEngineIntegrationTest#seedEmployee}) -- is unaffected: {@code ssoInclusion} is
     * non-null there, so this branch is not taken, and the {@code anyMatch} check below still returns
     * {@code false} for it exactly as before.
     */
    private static boolean hasBeenOnboardedForPayroll(
        long employeeId, Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee
    ) {
        Map<PayrollComponent, Boolean> ssoInclusion = ssoInclusionByEmployee.get(employeeId);
        if (ssoInclusion == null) {
            return true;
        }
        return ssoInclusion.keySet().stream().anyMatch(component -> component != PayrollComponent.SALARY);
    }

    /**
     * P0 fix (Opus review, 2026-07-30), layer 3 -- the read-time safety net {@link
     * PayrollClassificationReachabilityIntegrationTest
     * #payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas} demands.
     * Layers 1 (V100's migration backfill) and 2 ({@code EmployeeService#create}'s new call to {@link
     * PayrollRepository#seedComponentTaxTreatmentDefaults}) only reach an employee that either
     * existed when V100 ran or was created through the API. That reachability test's employee is
     * neither: it is inserted by raw SQL mid-test, mimicking an employee whose classification rows
     * were never written by ANY path (uat's {@code V900}, {@code db/migration-demo}'s seeds, any bulk
     * import -- the identical gap {@link #ssoInclusionFor} already closes for SSO inclusion). Without
     * this layer such an employee has ZERO stored {@code hr.payroll_component_tax_treatment} rows and
     * {@link PayrollCalculator#calculateClassified}'s classification gate 409s the entire run the
     * moment they carry a single non-zero special pay -- production-realistic (handoff section 9d:
     * employee 10012's real ฿407 พิเศษ 1), not a contrived edge case.
     *
     * <p><b>Why this does not weaken "HR sets every line, no silent default" (handoff section 1):</b>
     * that rule governs the EXPLICIT "not yet classified" state -- a row that EXISTS with {@code
     * tax_treatment = NULL}, which the handoff distinguishes deliberately from a row that is simply
     * ABSENT (see {@link PayrollRepository#findComponentTaxTreatmentsByEmployee}'s own javadoc on the
     * distinction). This method only ever synthesizes a default when the employee has NO ROW AT ALL --
     * the map lookup itself returns {@code null}, meaning classification has never been touched for
     * this employee through any path. The instant an employee has even ONE stored row (including an
     * explicit present-and-null "deliberately left unclassified" row from the matrix screen, or every
     * test in this suite that seeds a deliberately PARTIAL classification via {@code
     * AbstractPostgresIntegrationTest#seedRegularTaxTreatment}), this method returns that map
     * completely unchanged -- explicit HR intent, once expressed for an employee at all, is never
     * second-guessed. This mirrors {@link #ssoInclusionFor}'s identical reasoning for the sibling
     * matrix exactly.
     *
     * <p><b>{@code hasBeenOnboarded} -- found necessary by a regression this method's first version
     * caused, not designed up front:</b> an unconditional "zero rows -&gt; synthesize" rule collided
     * head-on with the ALREADY-GREEN {@code
     * PayrollClassifiedEngineIntegrationTest#unclassifiedNonZeroComponentRejectsTheRunNamingEmployeeAndComponent}
     * -- the test that pins down "HR sets every line, no silent default" in the first place. That
     * test's employee ALSO has zero tax-treatment rows (it seeds literally nothing before previewing),
     * and legitimately expects the 409. Worse, a first attempt at a distinguishing signal
     * (merely {@code ssoInclusionByEmployee.containsKey(employeeId)}) ALSO failed against that same
     * test: this file's own {@code seedEmployee} helper -- unrelated test convenience, nothing to do
     * with onboarding -- seeds exactly one incidental SSO row (SALARY only, via {@code
     * AbstractPostgresIntegrationTest#seedSsoIncluded}) for every employee it creates, so "has ANY SSO
     * row" was true for that test too. See {@link #hasBeenOnboardedForPayroll} for the signal that
     * actually distinguishes "a real onboarding path touched this employee's SSO matrix" (every
     * component, including several non-SALARY ones) from "a test fixture happens to carry one
     * unrelated SALARY-only row" -- the reachability test's employee has the former (it calls {@code
     * seedSsoInclusionDefaults} directly), the blocking test's employee has neither.
     *
     * <p>Defaults come from {@link PayrollRepository#defaultTaxTreatment}, the SAME safe-default logic
     * {@code V100} and {@code seedComponentTaxTreatmentDefaults} both use, so all three layers can
     * never independently drift onto different defaults for the same component.
     */
    private static Map<PayrollComponent, PayrollTaxTreatment> treatmentsFor(
        long employeeId,
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsByEmployee,
        boolean hasBeenOnboarded
    ) {
        Map<PayrollComponent, PayrollTaxTreatment> stored = treatmentsByEmployee.get(employeeId);
        if (stored == null && !hasBeenOnboarded) {
            return Map.of();
        }
        return mergeWithDefaults(stored);
    }

    /**
     * P1 fix (fifth Opus review, 2026-07-30) -- the "partial classification cliff". {@link
     * #treatmentsFor} used to be all-or-nothing PER EMPLOYEE: the instant {@code stored} was
     * non-null (i.e. HR had classified even ONE component for this employee), it was returned
     * VERBATIM, so every OTHER component silently lost its synthesized default and reverted to
     * "not yet classified" -- which {@link PayrollCalculator#requireEveryNonZeroComponentClassified}
     * then 409s for. Because {@code TaxTreatmentMatrixSection.save()} (PayrollPage.jsx) sends only
     * the edited DIFF, not the full resolved matrix, classifying ONE cell for ONE employee took that
     * employee from "zero rows, every component defaulted, run succeeds" to "one row, every OTHER
     * non-zero component unclassified, run 409s" -- and because {@link #preview} maps every active
     * employee through one stream, that 409 aborted the WHOLE period for EVERY employee, not just the
     * one HR edited.
     *
     * <p>Fixed by resolving PER MISSING COMPONENT instead of per employee: merge the synthesized
     * defaults UNDER whatever is actually stored, so a component with no stored row still gets its
     * default and a component with a stored row keeps exactly what is stored.
     *
     * <p><b>This must not weaken the "HR sets every line, no silent default" gate.</b> A component
     * that is PRESENT in {@code stored} with a {@code null} treatment means HR explicitly chose "not
     * yet classified" -- that is a deliberate signal, not an accident, and must still block a
     * non-zero amount. The distinction is carried entirely by {@code containsKey}: a component
     * present in {@code stored} (even with a null value) is left exactly as stored; only a component
     * genuinely ABSENT from {@code stored} -- one HR has never touched at all -- receives the
     * synthesized default. {@code stored} itself already preserves this distinction end to end (see
     * {@link PayrollRepository#findComponentTaxTreatmentsByEmployee}'s own javadoc), so this method
     * only has to read it correctly, not invent the distinction.
     */
    private static Map<PayrollComponent, PayrollTaxTreatment> mergeWithDefaults(
        Map<PayrollComponent, PayrollTaxTreatment> stored
    ) {
        Map<PayrollComponent, PayrollTaxTreatment> merged = new EnumMap<>(PayrollComponent.class);
        for (PayrollComponent component : PayrollComponent.values()) {
            if (component == PayrollComponent.SALARY || component == PayrollComponent.NON_TAXABLE_INCOME) {
                continue;
            }
            if (stored != null && stored.containsKey(component)) {
                // Present -- may be a real treatment or an explicit "not yet classified" null; HR's
                // stored intent always wins over a synthesized default, in either direction.
                merged.put(component, stored.get(component));
            } else {
                merged.put(component, PayrollRepository.defaultTaxTreatment(component));
            }
        }
        return merged;
    }

    /**
     * P1 fix (Opus review, 2026-07-30): {@code EmployeeService#create} seeds SSO-inclusion defaults
     * only on the API create path. An employee inserted by raw SQL afterwards (uat's {@code V900},
     * {@code db/migration-demo/V21}, any bulk import) never runs that call and ends up with ZERO
     * stored {@code hr.payroll_component_sso_inclusion} rows -- which used to fall through to {@code
     * Map.of()} at the call site below (every component absent, and {@link
     * PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput#ssoIncluded} treats absent as
     * excluded), so the SSO wage base silently computed to ฿0.00 with no error at all.
     *
     * <p>Fixed at this READ/RESOLUTION layer rather than chasing every writer (the seed call itself,
     * every demo/uat seed migration, any future bulk import): an employee with genuinely NO stored
     * rows gets the same company-wide default {@link PayrollRepository#seedSsoInclusionDefaults} would
     * have written ({@link PayrollRepository#defaultSsoIncluded}, factored out so both paths can never
     * independently drift). An employee with AT LEAST ONE stored row -- including every test in this
     * suite that seeds a deliberately PARTIAL inclusion matrix via {@code
     * AbstractPostgresIntegrationTest#seedSsoIncluded} to pin one specific component's inclusion or
     * exclusion -- is returned completely unchanged: this only fills the gap for an employee nothing
     * has ever touched, it does not change what "absent within an existing map" means.
     *
     * <p>Unlike {@link #treatmentsFor}, this one has no {@code hasBeenOnboarded} gate: SSO inclusion
     * has no "explicitly declined to classify" state to protect (a boolean has only two values, either
     * of which is a real answer), so there is no equivalent regression to guard against here.
     */
    private static Map<PayrollComponent, Boolean> ssoInclusionFor(
        long employeeId, Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee
    ) {
        Map<PayrollComponent, Boolean> stored = ssoInclusionByEmployee.get(employeeId);
        if (stored != null) {
            return stored;
        }
        Map<PayrollComponent, Boolean> defaults = new EnumMap<>(PayrollComponent.class);
        for (PayrollComponent component : PayrollComponent.values()) {
            defaults.put(component, PayrollRepository.defaultSsoIncluded(component));
        }
        return defaults;
    }

    private PayrollLineDto calculateLine(
        PayrollEmployeeSnapshot employee,
        PayrollEmployeeInputRequest input,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal welfarePay,
        PayrollYearToDate yearToDate,
        PayrollTaxAllowanceInput storedAllowances,
        BigDecimal leaveRefundDays,
        LocalDate payrollMonth,
        Map<PayrollComponent, PayrollTaxTreatment> componentTaxTreatments,
        Map<PayrollComponent, Boolean> componentSsoInclusion
    ) {
        // Withholding-tax override precedence (2026-07-24, V88): a per-run HR-typed value wins; else
        // the employee's standing override; else null (= compute normally). NULL is meaningful at every
        // level -- a 0 override (withhold nothing) is honoured and must not collapse to "no override",
        // so this is a plain null check, never a truthiness/sign test. The per-run TYPED value is what
        // gets persisted on the line (below) so it can carry forward; the standing value is NOT stored
        // on the line -- it re-applies from the employee record every run.
        BigDecimal perRunWithholdingOverride = input == null ? null : input.withholdingTaxOverride();
        BigDecimal effectiveWithholdingOverride = perRunWithholdingOverride != null
            ? perRunWithholdingOverride
            : employee.withholdingTaxOverride();

        // F2 fix (Opus review, 2026-07-30): the frontend used to expose per-diem AMOUNT inputs with no
        // basis selector, and this method passed input.perDiemBasis() straight through to the INSERT
        // unvalidated -- so Preview always succeeded (it never writes a row) but Process 500'd on
        // V97's chk_payroll_line_per_diem_basis_present CHECK constraint the moment HR typed a
        // per-diem amount without a basis. Validate here, before that INSERT is ever reached, and fail
        // with a clear Thai-language 400 naming the employee and the missing basis instead of a bare
        // DataIntegrityViolationException turning into a 500.
        BigDecimal perDiemExemptAmount = input == null ? BigDecimal.ZERO : safe(input.perDiemExempt());
        BigDecimal perDiemTaxableAmount = input == null ? BigDecimal.ZERO : safe(input.perDiemTaxable());
        if (perDiemExemptAmount.add(perDiemTaxableAmount).signum() > 0
            && (input == null || input.perDiemBasis() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "พนักงาน " + employee.employeeCode() + " " + employee.employeeName()
                    + " มีการจ่ายเบี้ยเลี้ยง (เบี้ยเลี้ยง ตจว/ตปท) แต่ไม่ได้ระบุฐานตามมาตรา 42"
                    + " (เหมาจ่ายตามอัตราราชการ มาตรา 42(2) หรือจ่ายจริงตามหน้าที่ มาตรา 42(1))"
                    + " กรุณาเลือกฐานก่อนประมวลผลเงินเดือน");
        }

        // ลูกค้าคืนสินค้า earned/unearned flag (handoff section 6): not yet earned reduces the
        // commission earning itself, PRE-TAX -- netted into COMMISSION_PAY here, before it ever
        // reaches PayrollCalculator, so the calculator only ever sees a single already-net commission
        // figure and applies the deduction a second time only for the already-earned clawback case.
        boolean customerReturnAlreadyEarned = input != null && Boolean.TRUE.equals(input.customerReturnAlreadyEarned());
        BigDecimal customerReturnDeduction = input == null ? BigDecimal.ZERO : safe(input.customerReturnDeduction());
        BigDecimal effectiveCommissionPay = commissionPay == null ? BigDecimal.ZERO : commissionPay;
        if (!customerReturnAlreadyEarned && customerReturnDeduction.signum() > 0) {
            effectiveCommissionPay = effectiveCommissionPay.subtract(customerReturnDeduction).max(BigDecimal.ZERO);
        }

        // Daily-rate support (2026-07-30): hr.employee.pay_type ('M'/'D', existed since V1) was never
        // read by payroll before this fix -- current_salary went straight into PayrollComponent.SALARY
        // as if it were always a monthly figure, so a daily-rate employee's DAY RATE was paid as their
        // MONTHLY salary (verified in production: employee 203, ฿450/day, would be paid ฿450 instead
        // of ฿11,250 for 25 days). For a 'D' employee, employee.baseSalary() (hr.employee.current_salary)
        // IS the per-day rate; this period's gross SALARY component is that rate multiplied by
        // HR-entered daysWorked (a null/omitted daysWorked contributes zero gross, same "0 unless
        // entered" convention as bonusPay/otherOneOffPay). A monthly employee is entirely unaffected:
        // daysWorked is never read for them, and grossSalaryComponent is employee.baseSalary() exactly
        // as before this change.
        boolean dailyRatePay = employee.dailyRatePay();
        BigDecimal enteredDaysWorked = input == null ? null : input.daysWorked();
        // Finding 4 fix (Opus review, 2026-07-30): @Max(31) on the request DTO is only enforced
        // through @Valid at the controller boundary (PayrollController's /preview and /process),
        // which every integration test in this package -- including the reviewer's own -- bypasses
        // by calling PayrollService directly. Re-check here, in the one place BOTH entry points
        // actually flow through, before daysWorked ever reaches the multiply below or an INSERT: a
        // calendar-impossible count (e.g. 45 in a 31-day month) must not be accepted and paid, and a
        // genuine typo (a 5-digit value) must not be left to overflow days_worked's NUMERIC(6,2)
        // column as a raw Postgres error that 500s the whole run. Mirrors the existing per-diem-basis
        // guard just above -- a clean Thai 400 instead of a bare DataIntegrityViolationException.
        if (dailyRatePay && enteredDaysWorked != null && enteredDaysWorked.compareTo(MAX_DAYS_WORKED) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "พนักงาน " + employee.employeeCode() + " " + employee.employeeName()
                    + " ระบุจำนวนวันทำงาน " + enteredDaysWorked.toPlainString()
                    + " วัน ซึ่งเกินจำนวนวันสูงสุดที่รับได้ในหนึ่งงวด (31 วัน) กรุณาตรวจสอบตัวเลขที่กรอกใหม่อีกครั้ง");
        }
        BigDecimal grossSalaryComponent = dailyRatePay
            ? employee.baseSalary().multiply(enteredDaysWorked == null ? BigDecimal.ZERO : enteredDaysWorked)
            : employee.baseSalary();
        // The employee's TRUE per-day rate, passed to the calculator so unpaidLeaveDeduction/
        // leaveDeductionRefund/hourlyRate are derived from it directly instead of from
        // grossSalaryComponent/30 (see PayrollClassifiedCalculationDtos#dailyRateOverride's own
        // javadoc for why that division would be wrong the moment daysWorked != 30). Null for a
        // monthly employee reproduces the pre-existing baseSalary/30 derivation exactly.
        BigDecimal dailyRateOverride = dailyRatePay ? employee.baseSalary() : null;

        List<BigDecimal> specialPays = input == null ? List.of() : input.specialPays();
        Map<PayrollComponent, BigDecimal> componentAmounts = new EnumMap<>(PayrollComponent.class);
        componentAmounts.put(PayrollComponent.SALARY, grossSalaryComponent);
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_1, amountAt(specialPays, 0));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_2, amountAt(specialPays, 1));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_3, amountAt(specialPays, 2));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_4, amountAt(specialPays, 3));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_5, amountAt(specialPays, 4));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_6, amountAt(specialPays, 5));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_7, amountAt(specialPays, 6));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_8, amountAt(specialPays, 7));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_9, amountAt(specialPays, 8));
        componentAmounts.put(PayrollComponent.OVERTIME_PAY, overtimePay == null ? BigDecimal.ZERO : overtimePay);

        // ⚠️ DOUBLE-PAY GUARD (V128 cutover). Until this change, welfare was keyed into payroll by
        // hand -- the welfare document §2.2 instructs exactly that ("รวบรวมข้อมูลการเบิก...ก่อนวันที่ 25
        // เพื่อคีย์ข้อมูลเข้าในระบบเงินเดือน"), and พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ) is where it went.
        // Now that approved claims feed WELFARE_PAY automatically, a month carrying BOTH figures for
        // the same employee pays that welfare twice.
        //
        // Refused loudly, naming the employee and both amounts, rather than trusting a process
        // change to stick: this is real money leaving the company, and the failure is invisible on
        // the payslip because both figures are legitimate-looking earnings lines. HR clears the
        // manual พิเศษ 9 entry, or -- if the พิเศษ 9 amount is genuinely something else (it is a
        // general เงินรางวัล slot) -- records it in another slot for this month.
        BigDecimal resolvedWelfarePay = welfarePay == null ? BigDecimal.ZERO : welfarePay;
        BigDecimal manualSpecialPay9 = amountAt(specialPays, 8);
        if (resolvedWelfarePay.signum() > 0 && manualSpecialPay9.signum() > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "พนักงาน " + employee.employeeCode() + " " + employee.employeeName()
                    + " มีสวัสดิการที่อนุมัติแล้ว " + resolvedWelfarePay.toPlainString() + " บาท"
                    + " ซึ่งระบบดึงเข้างวดนี้ให้อัตโนมัติแล้ว และยังมีการคีย์ พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)"
                    + " ไว้อีก " + manualSpecialPay9.toPlainString() + " บาท"
                    + " หากเป็นรายการสวัสดิการเดียวกันจะจ่ายซ้ำ กรุณาลบยอดที่คีย์ไว้ในช่อง พิเศษ 9"
                    + " (หากเป็นเงินรางวัลรายการอื่นจริง กรุณาบันทึกในช่องพิเศษอื่นสำหรับงวดนี้)");
        }
        componentAmounts.put(PayrollComponent.WELFARE_PAY, resolvedWelfarePay);
        componentAmounts.put(PayrollComponent.COMMISSION_PAY, effectiveCommissionPay);
        componentAmounts.put(PayrollComponent.MEAL_ALLOWANCE, input == null ? BigDecimal.ZERO : safe(input.mealAllowance()));
        componentAmounts.put(PayrollComponent.PER_DIEM_TAXABLE, input == null ? BigDecimal.ZERO : safe(input.perDiemTaxable()));
        componentAmounts.put(PayrollComponent.BONUS_PAY, input == null ? BigDecimal.ZERO : safe(input.bonusPay()));
        componentAmounts.put(PayrollComponent.OTHER_ONE_OFF_PAY, input == null ? BigDecimal.ZERO : safe(input.otherOneOffPay()));
        componentAmounts.put(PayrollComponent.DIRECTOR_REMUNERATION, employee.directorRemuneration());
        // The มาตรา 42 exempt slice of เบี้ยเลี้ยง joins NON_TAXABLE_INCOME rather than getting its own
        // component: it is outside the tax base AND the ประกันสังคม wage base, which is exactly what
        // NON_TAXABLE_INCOME already means. Giving it a component would oblige HR to classify a ป.96
        // treatment for money that is never taxed. It stays visible as its own figure on the line and
        // the payslip -- only the tax/SSO arithmetic pools it here.
        componentAmounts.put(PayrollComponent.NON_TAXABLE_INCOME,
            input == null ? BigDecimal.ZERO : safe(input.nonTaxableIncome()).add(safe(input.perDiemExempt())));

        // Deduction obligation tracking (issue #373, AUTHORISED PAYROLL BUSINESS-LOGIC CHANGE --
        // see the PR body). When this employee has a driving hr.deduction_obligation row for a
        // kind, its instructed monthly amount -- clamped to the remaining instructed total, and
        // auto-stopped at 0 once that total is reached unless HR has recorded an explicit override
        // -- REPLACES whatever HR typed this run for that field. An employee with no obligation on
        // file for a kind falls back to the HR-typed figure exactly as before this change (legacy
        // behaviour is unaffected). See DeductionObligationService#resolveMonthlyAmount.
        BigDecimal resolvedStudentLoanDeduction = deductionObligationService.resolveMonthlyAmount(
            employee.employeeId(), DeductionObligationKind.STUDENT_LOAN, payrollMonth,
            input == null ? null : input.studentLoanDeduction());
        BigDecimal resolvedLegalExecutionRequested = deductionObligationService.resolveMonthlyAmount(
            employee.employeeId(), DeductionObligationKind.LEGAL_EXECUTION, payrollMonth,
            input == null ? null : safe(input.legalExecutionDeduction()));

        PayrollClassifiedCalculation calculation = payrollCalculator.calculateClassified(new PayrollClassifiedCalculationInput(
            employee.employeeId(),
            employee.employeeCode() + " " + employee.employeeName(),
            componentAmounts,
            componentTaxTreatments,
            componentSsoInclusion,
            input == null ? BigDecimal.ZERO : input.unpaidLeaveDays(),
            leaveRefundDays == null ? BigDecimal.ZERO : leaveRefundDays,
            resolvedStudentLoanDeduction,
            input == null ? BigDecimal.ZERO : input.otherPretaxDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPostTaxDeductions(),
            // หักตามใบเตือน (handoff section 6): POST-TAX only now.
            input == null ? BigDecimal.ZERO : input.warningLetterDeduction(),
            customerReturnDeduction,
            customerReturnAlreadyEarned,
            resolvedLegalExecutionRequested,
            input == null ? null : input.garnishmentType(),
            mergeAllowances(storedAllowances, input),
            yearToDate,
            payrollMonth.getMonthValue(),
            // Rebase consequence (2026-07-29): retirementAllowance's taxYear parameter (117) must reach
            // calculateClassified too (see PayrollClassifiedCalculationDtos#taxYear javadoc). Derived
            // the same way the outer preview()/process() methods already derive it for the tax-
            // treatment/SSO-inclusion lookups above.
            payrollMonth.getYear(),
            effectiveWithholdingOverride,
            dailyRateOverride
        ));
        return new PayrollLineDto(
            null,
            employee.employeeId(),
            employee.employeeCode(),
            employee.employeeName(),
            employee.departmentName(),
            employee.bankName(),
            employee.bankAccount(),
            calculation.baseSalary(),
            calculation.dailyRate(),
            calculation.hourlyRate(),
            specialPayDtos(calculation.specialPays()),
            calculation.specialPayTotal(),
            calculation.overtimePay(),
            calculation.commissionPay(),
            calculation.grossEarnings(),
            calculation.nonTaxableIncome(),
            calculation.unpaidLeaveDays(),
            calculation.unpaidLeaveDeduction(),
            calculation.grossTaxableIncome(),
            calculation.ssoWageBase(),
            calculation.socialSecurity(),
            calculation.projectedAnnualIncome(),
            calculation.taxExpenseDeduction(),
            calculation.taxAllowanceTotal(),
            calculation.taxableAnnualIncome(),
            calculation.annualTax(),
            calculation.withholdingTax(),
            calculation.studentLoanDeduction(),
            calculation.legalExecutionDeduction(),
            calculation.otherPostTaxDeductions(),
            calculation.totalDeductions(),
            calculation.netPay(),
            calculation.calculationNote(),
            calculation.directorRemuneration(),
            calculation.warningLetterDeduction(),
            calculation.customerReturnDeduction(),
            calculation.otherPretaxDeduction(),
            calculation.leaveRefundDays(),
            calculation.leaveDeductionRefund(),
            // Persist the PER-RUN typed override only (nullable). Deliberately NOT the resolved
            // effective override: the standing employee value re-applies from the employee record every
            // run, so storing it on the line and carrying it forward would double-apply it. When HR
            // relied on the standing value this stays null and next month's carry-forward is empty --
            // exactly the desired "standing keeps applying, per-run field starts blank" behaviour.
            perRunWithholdingOverride,
            // ป.96/2543 limbs (V92, regular/variable 2-limb): calculateLine now runs exclusively through
            // calculateClassified (V96, regular/known/cumulative 3-limb -- see PayrollClassifiedCalculation
            // below), which has no concept of this older 2-limb split at all. Backfilled the same way
            // PayrollLineDto's own 40-arg legacy constructor already backfills the NEW limb fields for a
            // caller with no limb concept: everything attributed to the regular limb (grossTaxableIncome /
            // withholdingTax in full), nothing to the variable limb. These four columns are therefore
            // vestigial for every line processed from this rebase forward -- kept only so historical rows
            // written by the pre-task-2 engine still round-trip through this record's shape.
            calculation.grossTaxableIncome(),
            BigDecimal.ZERO,
            calculation.withholdingTax(),
            BigDecimal.ZERO,
            calculation.bonusPay(),
            calculation.otherOneOffPay(),
            // F3 fix (Opus review, 2026-07-30): this used to be hardcoded to ZERO on every processed
            // line -- see calculateClassified's own comment on excessWithheldToDate for the computation
            // and why it now flows through. Kept persisting it so PayslipRenderer's over-withholding
            // notice (line 158) and the ภ.ง.ด.1 working-paper figure spec section 8 requires are both
            // reachable again.
            calculation.excessWithheldToDate(),
            calculation.taxableIncomeRegularLimb(),
            calculation.taxableIncomeKnownLimb(),
            calculation.taxableIncomeCumulativeLimb(),
            calculation.withholdingTaxRegularLimb(),
            calculation.withholdingTaxCumulativeLimb(),
            calculation.customerReturnAlreadyEarned(),
            calculation.garnishmentType().name(),
            calculation.mealAllowance(),
            // perDiemExempt/perDiemBasis never reach the calculator: perDiemExempt is folded into
            // NON_TAXABLE_INCOME above (it has no ป.96 treatment to classify -- see PayrollComponent's
            // javadoc), and perDiemBasis is pure metadata with no arithmetic role at all. Both are
            // passed straight through from the request so they are still persisted on the line.
            input == null ? BigDecimal.ZERO : safe(input.perDiemExempt()),
            calculation.perDiemTaxable(),
            input == null || input.perDiemBasis() == null ? null : input.perDiemBasis().name(),
            // D1 fix (fourth reachability audit, 2026-07-30, V101): the raw request value, ALWAYS --
            // regardless of customerReturnAlreadyEarned -- so the form field (and the checkbox that
            // gates on it being > 0) round-trip after a reload. Deliberately NOT `customerReturnDeduction`
            // above, which is calculateClassified's POST-TAX bookkeeping figure and stays 0 in the
            // unearned path (the amount is instead netted pre-tax out of commissionPay, see this
            // method's own `effectiveCommissionPay` computation above).
            customerReturnDeduction,
            // Daily-rate support (2026-07-30): the raw HR-typed days-worked figure (nullable -- see
            // PayrollLineDto#daysWorked's own javadoc) and the employee's pay_type AS OF THIS RUN, so
            // the frontend can gate the days-worked input to daily-rate employees and the value
            // survives a reload.
            enteredDaysWorked,
            employee.payType(),
            // V123 (defect-2 fix): the uncapped SSO wage base the calculator already computed --
            // see PayrollLineDto#ssoWageGross's own javadoc. NOT calculation.ssoWageBase(), which
            // stays the capped figure tax/contribution math uses, unchanged.
            calculation.ssoWageBaseRaw(),
            // Issue #376, D-376-1 fix: the EXACT figure this run resolved and fed to
            // PayrollCalculator as garnishmentRequested -- see PayrollLineDto#legalExecutionRequested's
            // own javadoc for why this must be carried out here rather than re-derived later.
            resolvedLegalExecutionRequested,
            // V128: the welfare figure this run pulled in, carried out so it persists to
            // payroll_line.welfare_pay. Already in gross via componentAmounts above; this is what
            // keeps the breakdown readable after the period is re-read.
            resolvedWelfarePay
        );
    }

    private BigDecimal amountAt(List<BigDecimal> values, int index) {
        BigDecimal value = values == null || values.size() <= index ? null : values.get(index);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * จำนวนคราวที่ต้องจ่าย remaining in this tax year, INCLUDING this period — คำชี้แจง ภ.ง.ด.1 ข้อ 2.1.
     *
     * <p>Monthly payroll, so this is {@code 13 - month}: 12 in January, 1 in December. A mid-year
     * JOINER needs no special handling — their year-to-date is empty, so January..March simply never
     * enter the projection and an April start naturally projects over 9 periods, which is the RD's own
     * worked example in ข้อ 2.1.
     *
     * <p>A mid-year LEAVER is NOT handled, so ข้อ 2.10's final-period true-up does not happen. It WAS
     * implemented against {@code hr.resignation} and then removed (2026-07-29): the owner confirms
     * resignations are not recorded in this platform at all — they live in another system — so nothing
     * ever populates that table, and {@code findActiveEmployees} filters on {@code is_active} anyway,
     * which drops a leaver out of payroll before any of it could apply. Code that cannot execute is
     * worse than an acknowledged gap, because it reads as a compliance feature that works. ข้อ 2.10 is
     * on the known-gaps list pending resignation data from the other platform.
     */
    private int remainingPayPeriods(LocalDate payrollMonth) {
        return Math.max(1, 13 - payrollMonth.getMonthValue());
    }

    /**
     * C1: merges the standing stored declaration with this run's request body, field by field. A
     * non-null field on the request is an explicit in-run correction and wins; a null field falls back
     * to the stored standing declaration.
     */
    private PayrollTaxAllowanceInput mergeAllowances(PayrollTaxAllowanceInput stored, PayrollEmployeeInputRequest input) {
        PayrollTaxAllowanceInput base = stored == null ? PayrollTaxAllowanceInput.empty() : stored;
        if (input == null) {
            return base;
        }
        return new PayrollTaxAllowanceInput(
            firstNonNull(input.spouseAllowance(), base.spouseAllowance()),
            firstNonNull(input.childAllowance(), base.childAllowance()),
            firstNonNull(input.parentCareAllowance(), base.parentCareAllowance()),
            firstNonNull(input.disabledCareAllowance(), base.disabledCareAllowance()),
            firstNonNull(input.maternityAllowance(), base.maternityAllowance()),
            firstNonNull(input.lifeInsuranceAllowance(), base.lifeInsuranceAllowance()),
            firstNonNull(input.healthInsuranceAllowance(), base.healthInsuranceAllowance()),
            firstNonNull(input.parentHealthInsuranceAllowance(), base.parentHealthInsuranceAllowance()),
            firstNonNull(input.rmfAllowance(), base.rmfAllowance()),
            firstNonNull(input.ssfAllowance(), base.ssfAllowance()),
            firstNonNull(input.pensionInsuranceAllowance(), base.pensionInsuranceAllowance()),
            firstNonNull(input.thaiEsgAllowance(), base.thaiEsgAllowance()),
            firstNonNull(input.homeLoanInterestAllowance(), base.homeLoanInterestAllowance()),
            firstNonNull(input.educationDonation(), base.educationDonation()),
            firstNonNull(input.generalDonation(), base.generalDonation()),
            firstNonNull(input.politicalDonation(), base.politicalDonation()),
            // Head counts come from the stored ล.ย.01 declaration -- standing facts about the
            // employee's household, recorded with evidence, not something a payroll operator retypes.
            //
            // EXCEPT when the run body supplies an AMOUNT the stored declaration cannot support. The
            // amount and the count travel separately, so an employee with no stored row for whom HR
            // types childAllowance = 60,000 would otherwise meet a cap of zero and lose the whole
            // allowance silently. Deriving a count from the supplied amount in exactly that case is the
            // same decision taken in PayrollTaxAllowanceInput's legacy constructor and V93's backfill:
            // the amount is the only evidence there is, and reading it as an overstatement would delete
            // a real declaration.
            headCountFor(base.childCount(), input.childAllowance(), "30000"),
            base.childCountDouble(),
            headCountFor(base.disabledCareCount(), input.disabledCareAllowance(), "60000"),
            base.disabilityCardHolder(),
            input.parentCareCount() != null ? input.parentCareCount() : (base.parentCareCount() == null ? 0 : base.parentCareCount())
        );
    }

    /**
     * The head count to apply when the stored ล.ย.01 declaration has NONE and this run supplies an
     * amount: the smallest count that would permit that amount. A stored count always wins outright.
     *
     * <p>Deliberately narrow, and narrowed twice. It exists for exactly one case — an employee with no
     * stored declaration for whom HR types an allowance in the run body, who would otherwise meet a
     * cap of zero and lose it silently. It must NOT do more than that:
     *
     * <ul>
     *   <li>An earlier version took {@code max(declaredCount, impliedCount)} so a STALE count would be
     *       raised too. Review showed that destroyed the cap entirely: the amount fell back to the
     *       STORED figure when the run body omitted the field, so the count was raised to cover the
     *       very amount it exists to constrain, and a ฿300,000 declaration against one child was
     *       allowed in full. Direction of error was UNDER-withholding.</li>
     *   <li>So a stale or too-small count now CLAMPS, and {@code PayrollCalculator#clampedAllowanceNote}
     *       says so on the payslip. Clamp-and-warn is the correct answer to a declaration the head
     *       count cannot support; silently raising the cap is not.</li>
     * </ul>
     *
     * <p>{@code runBodyAmount} must be the request-body value ONLY, never the stored one — falling
     * back to stored is precisely the hole described above.
     */
    private int headCountFor(int storedCount, BigDecimal runBodyAmount, String perHead) {
        if (storedCount > 0 || runBodyAmount == null || runBodyAmount.signum() <= 0) {
            return storedCount;
        }
        return runBodyAmount
            .divide(new BigDecimal(perHead), 0, java.math.RoundingMode.CEILING)
            .intValueExact();
    }

    /**
     * The employee's age in the payroll month's TAX YEAR, for the ยกเว้นเงินได้ 190,000 available from
     * 65 (กฎกระทรวง ฉบับที่ 126).
     *
     * <p>Measured at 31 December of the tax year, not at the payroll month: the exemption belongs to
     * the tax year as a whole, so someone turning 65 in November is 65 for that year's withholding
     * from January. Returns 0 when the date of birth is unknown, which the calculator treats as
     * "do not grant the exemption on an assumption".
     */
    private int taxpayerAge(LocalDate dateOfBirth, LocalDate payrollMonth) {
        if (dateOfBirth == null) {
            return 0;
        }
        return Math.max(0, payrollMonth.getYear() - dateOfBirth.getYear());
    }

    private BigDecimal firstNonNull(BigDecimal requested, BigDecimal stored) {
        if (requested != null) {
            return requested;
        }
        return stored == null ? BigDecimal.ZERO : stored;
    }

    // ---- C1 / C2: HR-typed standing declarations, view broader than edit ----------------------

    public TaxAllowanceListResponse getTaxAllowances(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return new TaxAllowanceListResponse(taxYear, payrollRepository.findTaxAllowanceRows(taxYear));
    }

    @Transactional
    public TaxAllowanceListResponse upsertTaxAllowances(int taxYear, TaxAllowanceBulkUpsertRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<EmployeeTaxAllowanceUpsertRequest> items = request == null || request.items() == null
            ? List.of()
            : request.items();
        payrollRepository.upsertTaxAllowances(taxYear, items, actor.employeeId());
        TaxAllowanceListResponse result = new TaxAllowanceListResponse(taxYear, payrollRepository.findTaxAllowanceRows(taxYear));
        auditService.record(actor, "UPSERT_TAX_ALLOWANCES", "employee_tax_allowance", null,
            null, Map.of("taxYear", taxYear, "employeeIds", employeeIdsOf(items)));
        return result;
    }

    public YtdSeedListResponse getYtdSeed(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return new YtdSeedListResponse(taxYear, payrollRepository.findYtdSeedRows(taxYear));
    }

    @Transactional
    public YtdSeedListResponse upsertYtdSeed(int taxYear, YtdSeedBulkUpsertRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<YtdSeedUpsertRequest> items = request == null || request.items() == null ? List.of() : request.items();
        payrollRepository.upsertYtdSeed(taxYear, items, actor.employeeId());
        YtdSeedListResponse result = new YtdSeedListResponse(taxYear, payrollRepository.findYtdSeedRows(taxYear));
        auditService.record(actor, "UPSERT_PAYROLL_YTD_SEED", "payroll_year_to_date_seed", null,
            null, Map.of("taxYear", taxYear, "employeeIds", ytdEmployeeIdsOf(items)));
        return result;
    }

    // ---- P0 fix (Opus review, 2026-07-30): tax-treatment matrix HTTP surface -------------------
    //
    // PayrollRepository#upsertComponentTaxTreatment existed since task 1 with zero callers in
    // src/main -- nothing could ever write hr.payroll_component_tax_treatment on a real deployment,
    // so PayrollCalculator#calculateClassified's classification gate (correct on its own terms) 409s
    // every run for any employee with a non-zero, non-SALARY component. Same view/edit split as the
    // C1/C2 declarations above (HR+CEO view, HR-only edit).

    public PayrollClassificationDtos.TaxTreatmentListResponse getComponentTaxTreatments(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return componentTaxTreatmentsSnapshot(taxYear);
    }

    /**
     * Minor fix (fourth reachability audit, 2026-07-30): {@code @Valid} on {@code PayrollController
     * #putComponentTaxTreatments}'s {@code @RequestBody List<ComponentTaxTreatmentUpsertRequest>} is
     * INERT -- Spring's {@code SpringValidatorAdapter} does not cascade {@code @Valid} onto the
     * ELEMENTS of a bare {@code List} request body, only onto fields of an enclosing object. So
     * {@code ComponentTaxTreatmentUpsertRequest}'s own {@code @NotNull employeeId}/{@code component}
     * never actually fire, and a malformed body (a null {@code employeeId}, say) reaches this method
     * and the repository unvalidated -- the frontend always populates both, so this is not a
     * reachability blocker in practice, but a malformed client would previously hit an unclear
     * {@code DataIntegrityViolationException} (or worse) instead of a clean 400.
     *
     * <p>Not fixed by wrapping the list in a new record: {@code
     * PayrollClassificationReachabilityIntegrationTest#hrHasSomeHttpWayToClassifyAComponentBeforeTheEngineDemandsIt}
     * reflects on {@code PayrollController}'s methods for a parameter whose generic type name
     * literally contains {@code ComponentTaxTreatmentUpsertRequest} -- a wrapper type's name would
     * not, and that test is kept verbatim per instruction. Validated explicitly here instead, at the
     * same layer {@link #upsertComponentTaxTreatments}'s sibling validation (F2's per-diem-basis
     * check) already uses.
     */
    @Transactional
    public PayrollClassificationDtos.TaxTreatmentListResponse upsertComponentTaxTreatments(
        int taxYear, List<ComponentTaxTreatmentUpsertRequest> items, UserPrincipal actor
    ) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<ComponentTaxTreatmentUpsertRequest> safeItems = items == null ? List.of() : items;
        for (int index = 0; index < safeItems.size(); index += 1) {
            ComponentTaxTreatmentUpsertRequest item = safeItems.get(index);
            if (item == null || item.employeeId() == null || item.component() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการที่ " + (index + 1) + " ของการจัดประเภทภาษีหัก ณ ที่จ่ายไม่ถูกต้อง: "
                        + "ต้องระบุ employeeId และ component เสมอ");
            }
        }
        payrollRepository.upsertComponentTaxTreatment(taxYear, safeItems, actor.employeeId());
        PayrollClassificationDtos.TaxTreatmentListResponse result = componentTaxTreatmentsSnapshot(taxYear);
        auditService.record(actor, "UPSERT_PAYROLL_COMPONENT_TAX_TREATMENT", "payroll_component_tax_treatment", null,
            null, Map.of("taxYear", taxYear, "employeeIds",
                safeItems.stream().map(ComponentTaxTreatmentUpsertRequest::employeeId).distinct().toList()));
        return result;
    }

    /**
     * F2 fix (fifth Opus review, 2026-07-30): used to project {@code
     * byEmployee.getOrDefault(id, Map.of())} -- the RAW stored map -- straight onto the screen,
     * regardless of what {@link #treatmentsFor} would actually resolve for that same employee at
     * payroll time. Now resolves the SAME merged/effective map {@code treatmentsFor} does (same
     * {@code hasBeenOnboardedForPayroll} signal, same defaults), so the screen can never show a
     * different classification than the engine applies, and separately reports which components HR
     * actually has a stored row for, so the frontend can distinguish "HR set this" from "the system
     * is defaulting this".
     */
    private PayrollClassificationDtos.TaxTreatmentListResponse componentTaxTreatmentsSnapshot(int taxYear) {
        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> byEmployee =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear);
        Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee =
            payrollRepository.findComponentSsoInclusionByEmployee(taxYear);
        List<PayrollClassificationDtos.TaxTreatmentMatrixRow> items = employees.stream()
            .map(employee -> {
                long employeeId = employee.employeeId();
                Map<PayrollComponent, PayrollTaxTreatment> stored = byEmployee.get(employeeId);
                boolean onboarded = hasBeenOnboardedForPayroll(employeeId, ssoInclusionByEmployee);
                Map<PayrollComponent, PayrollTaxTreatment> effective =
                    treatmentsFor(employeeId, byEmployee, onboarded);
                Set<PayrollComponent> explicit = stored == null ? Set.of() : Set.copyOf(stored.keySet());
                return new PayrollClassificationDtos.TaxTreatmentMatrixRow(
                    employeeId,
                    employee.employeeCode(),
                    employee.employeeName(),
                    effective,
                    explicit);
            })
            .toList();
        return new PayrollClassificationDtos.TaxTreatmentListResponse(taxYear, items);
    }

    private List<Long> employeeIdsOf(List<EmployeeTaxAllowanceUpsertRequest> items) {
        return items.stream().map(EmployeeTaxAllowanceUpsertRequest::employeeId).toList();
    }

    private List<Long> ytdEmployeeIdsOf(List<YtdSeedUpsertRequest> items) {
        return items.stream().map(YtdSeedUpsertRequest::employeeId).toList();
    }

    /**
     * Commission-payroll weighted-base + manual-entries fix (2026-07-23): delegates to {@link
     * CommissionService#payrollCommissionTotalsByEmployee}, the exact same weighted-tier +
     * approved-manual-entries aggregation {@link CommissionService#payrollReadySummary} uses for
     * what HR sees on the payroll-ready screen -- the two paths now share one implementation and
     * can never diverge again.
     *
     * <p>This used to reimplement the tier/VAT math independently here, two bugs deep: (1) it
     * summed each APPROVED record's already-2dp-rounded {@code commissionableBase} UNWEIGHTED
     * instead of the weighted, full-precision monthly tier base, underpaying a rep with a
     * 2x/3x-weighted receipt (owner-reconciled "jennet" case: weighted 67,849.23 vs. the old
     * unweighted 67,390.34); and (2) it excluded approved manual entries (ADJUSTMENT/MANAGER/
     * STOCK_BONUS/INCENTIVE, V84) from the payroll figure entirely, even though real payroll pays
     * tier commission PLUS those manual entries (same "jennet" case with a 15,000 INCENTIVE added:
     * 82,849.23). See {@code PayrollCommissionWeightedBaseIntegrationTest} for the real-DB
     * regression coverage of both.
     */
    private Map<Long, BigDecimal> commissionPayByEmployee(LocalDate payrollMonth) {
        return commissionService.payrollCommissionTotalsByEmployee(payrollMonth);
    }

    /**
     * Slot labels, ALIGNED TO THE ACCOUNTANT'S WORKBOOK (2026-07-29, owner decision).
     *
     * <p>`2026.xlsx` numbers these differently from the system's original labels: it carries
     * ค่าเช่าบ้าน as พิเศษ 2, which shifted every later number by one. The names always matched; only
     * the numbers disagreed, so the system's slot 6 (คอมมิชชั่น) was the workbook's พิเศษ 7 and every
     * reconciliation needed a translation table in someone's head.
     *
     * <p>The first decision was to leave the system alone and have the accountant renumber, because
     * renumbering would have redefined 149 processed rows across five filed months. The owner then
     * confirmed **nothing has ever been processed or paid from this ERP** — all five runs were tests,
     * now VOIDed — so that argument collapsed and the decision was reversed. With every period VOID
     * this is a pure relabel: no stored figure moves and no filed return exists to be affected.
     *
     * <p>⚠️ Branch 117 hardcodes commission at slot 6. It is slot 7 now. See the handoff's cross-branch
     * break note — merging 117 without updating that constant puts commission in the wrong ป.96 limb
     * and annualises ค่า GPRS in its place, silently.
     */
    private List<PayrollSpecialPayDto> specialPayDtos(List<BigDecimal> specialPays) {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", specialPays.get(0)),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2 (ค่าเช่าบ้าน)", specialPays.get(1)),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3 (เบี้ยเลี้ยงประจำ)", specialPays.get(2)),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4 (ค่าตำแหน่ง)", specialPays.get(3)),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5 (เบี้ยขยันประจำ)", specialPays.get(4)),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6 (ค่า GPRS)", specialPays.get(5)),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7 (คอมมิชชั่น)", specialPays.get(6)),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8 (ทำได้ตาม KPI)", specialPays.get(7)),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", specialPays.get(8))
        );
    }

    private List<PayrollEmployeeInputRequest> safeInputs(List<PayrollEmployeeInputRequest> inputs) {
        return inputs == null ? List.of() : inputs;
    }

    private LocalDate normalizeMonth(LocalDate payrollMonth) {
        if (payrollMonth == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุงวดเงินเดือน");
        }
        return payrollMonth.withDayOfMonth(1);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private BigDecimal sum(List<PayrollLineDto> lines, MoneyExtractor extractor) {
        return lines.stream().map(extractor::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void auditPayrollAccess(String action, UserPrincipal actor, PayrollPeriodDto period, String fields) {
        String targetEmployeeIds = period.lines().stream()
            .map(PayrollLineDto::employeeId)
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        AUDIT.info(
            "sensitive_data_access action={} actorId={} actorEmail=\"{}\" payrollPeriodId={} payrollMonth={} targetEmployeeIds=\"{}\" resultCount={} fields=\"{}\"",
            action,
            actor.id(),
            actor.email(),
            period.id(),
            period.payrollMonth(),
            targetEmployeeIds,
            period.lines().size(),
            fields);
    }

    private void auditPayrollLineAccess(String action, UserPrincipal actor, PayrollPeriodDto period, PayrollLineDto line, String fields) {
        AUDIT.info(
            "sensitive_data_access action={} actorId={} actorEmail=\"{}\" payrollPeriodId={} payrollMonth={} targetEmployeeIds=\"{}\" resultCount={} fields=\"{}\"",
            action,
            actor.id(),
            actor.email(),
            period.id(),
            period.payrollMonth(),
            line.employeeId(),
            1,
            fields);
    }

    private Map<String, Object> auditPayload(PayrollPeriodDto period, PayrollLineDto line) {
        return Map.of(
            "periodId", period.id(),
            "payrollMonth", period.payrollMonth(),
            "lineId", line.id(),
            "employeeId", line.employeeId());
    }

    private interface MoneyExtractor {
        BigDecimal value(PayrollLineDto line);
    }
}
