package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;

/**
 * Welfare ("สวัสดิการ") requests. <b>Every type is approved by the CEO and only the CEO, in a single
 * stage, for every employee</b> — there is no manager stage and no per-type exception. That is an
 * owner ruling, and it is the one way this class deliberately does NOT mirror
 * {@code th.co.glr.hr.overtime.OvertimeService}, which keeps a manager → CEO pipeline wherever the
 * employee's ฝ่าย has a ผู้จัดการ.
 *
 * <p>Note this is stricter than the signed 2018 welfare policy, which lets a driver or loader claim
 * travel per-diem straight from หัวหน้าฝ่าย. The stricter rule is intentional.
 *
 * <p>{@code MANAGER_APPROVED} survives in {@link SpecialMoneyStatus} and in {@code chk_smr_status}
 * only for rows written before the manager stage was removed; nothing can enter that state now.
 *
 * <p>{@code managesEmployee} still exists here, but only for <em>read scoping and submit-on-behalf</em>
 * — a ฝ่าย manager may file for their team and see their team's requests. It grants no approval.
 */
@Service
public class SpecialMoneyService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> VIEW_ALL_ROLES = Set.of("hr", "ceo");

    private final SpecialMoneyRepository repository;
    private final SpecialMoneyPolicyEvaluator evaluator;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final CeoApproverRepository ceoApprovers;

    public SpecialMoneyService(
            SpecialMoneyRepository repository,
            SpecialMoneyPolicyEvaluator evaluator,
            AuditService auditService,
            NotificationService notificationService,
            AppProperties appProperties,
            CeoApproverRepository ceoApprovers) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.appProperties = appProperties;
        this.ceoApprovers = ceoApprovers;
    }

    public List<SpecialMoneyRequestDto> list(
            UserPrincipal user,
            LocalDate fromDate,
            LocalDate toDate,
            Long requestedEmployeeId,
            String requestedStatus,
            String requestType) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfMonth(1) : fromDate;
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
        }

        Long employeeId = requestedEmployeeId;
        Long managerEmployeeId = null;
        Long managerDivisionId = null;
        if (!canViewAll(user)) {
            managerEmployeeId = requireEmployeeId(user);
            managerDivisionId = user.manager() ? user.divisionId() : null;
            if (requestedEmployeeId != null && !canAccessEmployee(user, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }

        return repository.findRequests(new SpecialMoneyFilter(
            employeeId,
            managerEmployeeId,
            managerDivisionId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus),
            requestType
        ));
    }

    public List<SpecialMoneyEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        Long managerDivisionId = user.manager() ? user.divisionId() : null;
        return repository.findEmployeeOptions(actorEmployeeId, managerDivisionId, canViewAll(user));
    }

    public SpecialMoneyUsageDto usage(long employeeId, int year, UserPrincipal user) {
        if (!canAccessEmployee(user, employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        UsageSnapshot snapshot = repository.findUsage(employeeId, year);
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        snapshot.approvedAmountThisYearByType().forEach((type, amount) -> amounts.put(type.name(), amount));
        Map<String, Integer> lifetimeCounts = new LinkedHashMap<>();
        snapshot.activeCountLifetimeByType().forEach((type, count) -> lifetimeCounts.put(type.name(), count));
        // The snapshot has always carried the per-year count for the once-per-year uniform rule;
        // it was simply dropped here, so the UI could not warn "you already filed this year" and
        // the employee only found out from the 400 on submit.
        Map<String, Integer> yearCounts = new LinkedHashMap<>();
        snapshot.activeCountThisYearByType().forEach((type, count) -> yearCounts.put(type.name(), count));
        return new SpecialMoneyUsageDto(employeeId, year, amounts, lifetimeCounts, yearCounts);
    }

    @Transactional
    public SpecialMoneyRequestDto submit(String requestTypeRaw, SubmitSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long employeeId = resolveTargetEmployee(request.employeeId(), user);
        validateEmployee(employeeId);
        SpecialMoneyType type = parseType(requestTypeRaw);

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        EmployeeEligibilitySnapshot eligibility = repository.findEligibility(employeeId, today)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน"));
        UsageSnapshot usage = repository.findUsage(employeeId, usageYear(today));
        PolicyAmounts amounts = repository.findPolicyAmounts(type.name(), today);
        Set<String> excludedProvinces = repository.findExcludedProvinces();

        PolicyDecision decision = evaluator.evaluate(type, request, eligibility, usage, amounts, excludedProvinces);
        if (!decision.violations().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, String.join("; ", decision.violations()));
        }

        long id = repository.create(employeeId, actorEmployeeId, request, type, decision);
        SpecialMoneyRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_SPECIAL_MONEY_REQUEST", "special_money_request", id, null, created);
        notifySubmitted(created);
        return created;
    }

    @Transactional
    public SpecialMoneyRequestDto approve(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status == SpecialMoneyStatus.SUBMITTED) {
            return ceoApproveFrom(SpecialMoneyStatus.SUBMITTED, id, request, user, actorEmployeeId, existing);
        }
        if (status == SpecialMoneyStatus.MANAGER_APPROVED) {
            // Legacy rows only. Welfare no longer has a manager stage (see the class Javadoc), so
            // nothing new can enter this state -- but rows parked here before the change still need
            // a way out, and it is the same CEO decision either way.
            return ceoApproveFrom(SpecialMoneyStatus.MANAGER_APPROVED, id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
    }

    /**
     * The CEO's approval — the only approval welfare has.
     *
     * <p>{@code from == SUBMITTED} is the live route for every request. {@code from ==
     * MANAGER_APPROVED} exists only to clear rows written before the manager stage was removed. The
     * amount, cap-override and payroll-month logic is identical for both; only the status the
     * UPDATE accepts differs, so a legacy row cannot be approved under a different policy than a
     * fresh one.
     */
    private SpecialMoneyRequestDto ceoApproveFrom(
            SpecialMoneyStatus from,
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        boolean direct = from == SpecialMoneyStatus.SUBMITTED;
        requireCeo(user);
        requireEvidence(existing);

        BigDecimal approvedAmount = request != null && request.approvedAmount() != null
            ? request.approvedAmount()
            : existing.requestedAmount();
        String capOverrideReason = request == null ? null : blankToNull(request.capOverrideReason());

        SpecialMoneyType type = parseType(existing.requestType());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        // The 25th-of-month payroll cutoff. Rolling forward past an already-PROCESSED month is
        // deliberate: payroll writes a processed period once, so a request landing in a closed month
        // would be approved and then never paid.
        //
        // Computed HERE, before the usage/cap recheck below, so the recheck can key the annual-cap
        // lookup on THIS row's own payrollMonth (see usageYear's Javadoc) rather than on
        // existing.eventDate()'s year, which the employee supplied and does not bound. approvedOn
        // used to be a second, separate `LocalDate.now(BUSINESS_ZONE)` call made later in this
        // method; folded into `today` since both name the same instant and a request evaluated
        // across a real midnight boundary should not see two different "todays".
        int cutoffDay = appProperties.getSpecialMoney().getPayrollCutoffDay();
        LocalDate payrollMonth = today.getDayOfMonth() <= cutoffDay
            ? today.withDayOfMonth(1)
            : today.plusMonths(1).withDayOfMonth(1);
        while (repository.payrollMonthProcessed(payrollMonth)) {
            payrollMonth = payrollMonth.plusMonths(1);
        }

        EmployeeEligibilitySnapshot eligibility = repository.findEligibility(existing.employeeId(), today)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน"));
        // The AUTHORITATIVE cap-year check (see usageYear's Javadoc): the year this approval's own
        // payrollMonth falls in, never existing.eventDate()'s year.
        UsageSnapshot usage = repository.findUsage(existing.employeeId(), usageYear(payrollMonth));
        PolicyAmounts amounts = repository.findPolicyAmounts(type.name(), today);
        Set<String> excludedProvinces = repository.findExcludedProvinces();

        // Re-run the evaluator to learn the policy ceiling for this type/employee.
        //
        // The recheck is built from what the EMPLOYEE asked for, not from the CEO's chosen amount.
        // Substituting the CEO's figure -- as this did until 2026-08-03 -- made the guard below
        // structurally dead for every uncapped type: UNIFORM_NEW_STAFF, TRAVEL_LODGING, TRAINING
        // and OTHER all return `requestedAmount` as their eligible amount, so feeding in the
        // approved amount made eligibleAmount == approvedAmount and the comparison could never be
        // true. A CEO could approve ฿50,000 against a ฿5,000 request and never be asked why.
        //
        // Reading from the original request makes the ceiling mean "the cap, or failing a cap, what
        // was actually requested" -- so approving MORE than the policy allows, or more than the
        // employee asked for, both now require a written reason.
        //
        // We deliberately do not gate on the recheck's eligibility violations (e.g.
        // once-per-lifetime): this request is itself counted in "usage" (which spans SUBMITTED,
        // MANAGER_APPROVED and APPROVED alike) and would otherwise trip its own guard.
        SubmitSpecialMoneyRequest recheckRequest = new SubmitSpecialMoneyRequest(
            existing.employeeId(),
            existing.eventDate(),
            existing.eventEndDate(),
            existing.receiptDate(),
            existing.quantity(),
            existing.requestedAmount(),
            existing.reason(),
            existing.detail());
        PolicyDecision recheck = evaluator.evaluate(type, recheckRequest, eligibility, usage, amounts, excludedProvinces);

        boolean exceedsCap = approvedAmount.compareTo(recheck.eligibleAmount()) > 0;
        if (exceedsCap && capOverrideReason == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามนโยบายหรือเกินจำนวนที่พนักงานขอเบิก");
        }

        int updated = direct
            ? repository.ceoDirectApprove(
                id, actorEmployeeId, approvedAmount, payrollMonth, capOverrideReason, note(request))
            : repository.ceoApprove(
                id, actorEmployeeId, approvedAmount, payrollMonth, capOverrideReason, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(
            user,
            direct ? "CEO_APPROVE_SPECIAL_MONEY_REQUEST" : "CEO_APPROVE_LEGACY_MANAGER_APPROVED_SPECIAL_MONEY_REQUEST",
            "special_money_request",
            id,
            existing,
            after);
        notifyCeoApproved(after);
        return after;
    }

    @Transactional
    public SpecialMoneyRequestDto reject(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status == SpecialMoneyStatus.SUBMITTED) {
            // Symmetric with approve(): the sole reviewer must be able to refuse as well as accept,
            // or a request could only ever be approved.
            return ceoRejectFrom(SpecialMoneyStatus.SUBMITTED, id, request, user, actorEmployeeId, existing);
        }
        if (status == SpecialMoneyStatus.MANAGER_APPROVED) {
            return ceoRejectFrom(SpecialMoneyStatus.MANAGER_APPROVED, id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
    }

    /**
     * The CEO's rejection. As with {@link #ceoApproveFrom}, {@code MANAGER_APPROVED} is reachable
     * only for rows written before welfare's manager stage was removed.
     *
     * <p>{@code repository.reject} is guarded on {@code status = 'SUBMITTED'} and
     * {@code repository.ceoReject} on {@code status = 'MANAGER_APPROVED'}; neither writes approver
     * columns, so the two differ only in which row they will touch.
     */
    private SpecialMoneyRequestDto ceoRejectFrom(
            SpecialMoneyStatus from,
            long id,
            ReviewSpecialMoneyRequest request,
            UserPrincipal user,
            Long actorEmployeeId,
            SpecialMoneyRequestDto existing) {
        requireCeo(user);
        int updated = from == SpecialMoneyStatus.SUBMITTED
            ? repository.reject(id, actorEmployeeId, note(request))
            : repository.ceoReject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "CEO_REJECT_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyRejected(after, actorEmployeeId);
        return after;
    }

    @Transactional
    public SpecialMoneyRequestDto cancel(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        SpecialMoneyRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        boolean isEmployee = existing.employeeId() == actorEmployeeId;
        // S2 (pre-existing bug, not introduced by this branch): requestedById() is a boxed Long and
        // was compared with `==`, i.e. REFERENCE equality. Real employee ids in this system are 4-5
        // digits (10025, 142, ...), well outside Java's Long cache (-128..127), so two distinct Long
        // instances holding the identical value never compared equal here -- isRequester was ALWAYS
        // false in production, silently disabling the entire on-behalf-cancel path for anyone whose
        // id fell outside the cache. Fixed to value equality via .equals().
        boolean isRequester = existing.requestedById() != null && existing.requestedById().equals(actorEmployeeId);
        if (!isEmployee && !isRequester) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (!"SUBMITTED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ยกเลิกได้เฉพาะคำขอเงินพิเศษที่ยังไม่ได้รับการพิจารณาเท่านั้น");
        }

        int updated = repository.cancel(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ไม่สามารถยกเลิกได้แล้ว");
        }
        SpecialMoneyRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_SPECIAL_MONEY_REQUEST", "special_money_request", id, existing, after);
        notifyCancelled(after, isEmployee, actorEmployeeId, user.name());
        return after;
    }

    /**
     * Notification coverage gap B: cancelling a request used to notify nobody, so a withdrawn item
     * sat in the CEO's queue forever. {@code cancel} is reachable ONLY from {@code SUBMITTED} (see
     * the status guard above) and welfare has exactly one reviewing stage -- the CEO (see the class
     * Javadoc) -- so the pending party is always {@code ceoApprovers.findEmployeeIds()},
     * the same resolution {@link #notifySubmitted} uses; there is no manager-stage/already-decided
     * branching to reason about here, unlike leave/overtime.
     *
     * <p>Unlike leave/overtime, {@code repository.cancel} always writes the actor as {@code
     * reviewed_by_id} (never {@code null} -- see {@code SpecialMoneyRepository#cancel}), so that
     * column cannot tell self-cancel apart from on-behalf-cancel here; {@code cancelledBySelf} (the
     * caller's own {@code isEmployee} check) is passed through explicitly instead.
     *
     * <p>Review finding (BLOCKING 1): the CEO-facing message used to hardcode {@code
     * request.employeeName()} as if the employee themselves always did the cancelling, even on the
     * on-behalf-cancel path where a DIFFERENT person (a ฝ่าย manager who filed for their team --
     * {@code isRequester}, see {@link #cancel}) is the one actually cancelling. Fixed by threading
     * {@code actorEmployeeId}/{@code actorName} (the caller of {@link #cancel}) through: the message
     * is worded from the actual actor, and the CEO loop skips {@code actorEmployeeId} -- nobody is
     * ever notified about their own action.
     */
    private void notifyCancelled(
            SpecialMoneyRequestDto request, boolean cancelledBySelf, long actorEmployeeId, String actorName) {
        String actorLabel = actorName == null || actorName.isBlank() ? "ผู้ยื่นคำขอ" : actorName;
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_CANCELLED",
            "คำขอเงินสวัสดิการถูกยกเลิก",
            cancelledBySelf
                ? "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกยกเลิกเรียบร้อยแล้ว"
                // Nit fix (review, second pass): this used to omit WHO cancelled it on the employee's
                // behalf ("...แทนคุณ" with no name), unlike the Leave/OT counterparts, which both name
                // the actor -- now consistent with actorLabel.
                : "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกยกเลิกโดย " + actorLabel + " แทนคุณ",
            "/employee-requests",
            true
        );
        for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
            if (ceoEmployeeId == actorEmployeeId) {
                continue;
            }
            notificationService.notify(
                ceoEmployeeId,
                "SPECIAL_MONEY_CANCELLED",
                "คำขอเงินสวัสดิการที่รออนุมัติถูกยกเลิก",
                actorLabel + " ยกเลิกคำขอ " + request.requestType() + " วันที่ " + request.eventDate(),
                "/employee-requests",
                true
            );
        }
    }

    // ---------------------------------------------------------------------
    // Gates
    // ---------------------------------------------------------------------

    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId != actorEmployeeId && !managesEmployee(targetEmployeeId, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "พนักงานสามารถยื่นคำขอเงินพิเศษให้ตนเองเท่านั้น");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!repository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน");
        }
    }

    /**
     * Refuses to approve an evidence-required type with nothing attached.
     *
     * <p>{@code SpecialMoneyType.evidenceRequired()} existed since 2018 but was only ever returned
     * to the UI as a display flag — nothing enforced it, and there was no upload endpoint at all, so
     * the CEO approved money with no document trail whatsoever.
     *
     * <p><b>Presence is all this can check.</b> The policy document names a specific document per
     * type (บัตรเชิญ, รูปถ่าย, ใบสุทธิ, สูติบัตร, ใบมรณบัตร, ใบเสร็จ); no server can verify that an
     * uploaded file IS that document. The type-specific requirement is surfaced to the uploader in
     * the UI and is the reviewer's job to check — this gate only guarantees there is something to
     * check.
     */
    private void requireEvidence(SpecialMoneyRequestDto existing) {
        SpecialMoneyType type = parseType(existing.requestType());
        if (!type.evidenceRequired()) {
            return;
        }
        if (repository.countAttachments(existing.id()) == 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "คำขอประเภท " + type.thaiLabel() + " ต้องแนบเอกสารหลักฐานก่อนจึงจะอนุมัติได้");
        }
    }

    // ---------------------------------------------------------------------
    // Evidence attachments
    // ---------------------------------------------------------------------

    /**
     * Attaches a piece of evidence. Only the employee or whoever filed on their behalf may upload,
     * and only while the request is still SUBMITTED — once a decision is recorded the evidence it
     * was based on must not change underneath it.
     */
    /**
     * The upload gate, split out so the controller can authorize BEFORE writing the file to disk.
     * Called again inside {@link #addAttachment} — the two calls are cheap, and leaving the write
     * path unguarded on the assumption that the caller checked first is how that guarantee decays.
     */
    public void requireCanAttach(long id, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);

        boolean isEmployee = existing.employeeId() == actorEmployeeId;
        // S2-shaped bug, same fix as #cancel: requestedById() is a boxed Long and must be compared
        // by value, not reference.
        boolean isRequester = existing.requestedById() != null && existing.requestedById().equals(actorEmployeeId);
        if (!isEmployee && !isRequester) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะผู้ยื่นคำขอเท่านั้นที่แนบเอกสารได้");
        }
        if (!"SUBMITTED".equals(existing.status())) {
            throw new ApiException(
                HttpStatus.CONFLICT, "แนบเอกสารได้เฉพาะคำขอที่ยังไม่ได้รับการพิจารณาเท่านั้น");
        }
    }

    @Transactional
    public SpecialMoneyAttachmentDto addAttachment(
            long id, String fileName, String storagePath, String mimeType, Long sizeBytes, UserPrincipal user) {
        requireCanAttach(id, user);
        Long actorEmployeeId = requireEmployeeId(user);

        long attachmentId =
            repository.addAttachment(id, actorEmployeeId, fileName, storagePath, mimeType, sizeBytes);
        auditService.record(
            user, "ADD_SPECIAL_MONEY_ATTACHMENT", "special_money_request_attachment", attachmentId, null, fileName);
        return repository.findAttachments(id).stream()
            .filter(attachment -> attachment.id() == attachmentId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ไม่พบเอกสารที่เพิ่งแนบ"));
    }

    /** Evidence is readable by anyone who may read the request itself. */
    public List<SpecialMoneyAttachmentDto> listAttachments(long id, UserPrincipal user) {
        SpecialMoneyRequestDto existing = requireRequest(id);
        if (!canAccessEmployee(user, existing.employeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return repository.findAttachments(id);
    }

    /**
     * Resolves an attachment for download, authorizing against its OWNING REQUEST rather than the
     * attachment id. An attachment id is guessable; without this the file store would be a way to
     * read other people's medical receipts and death certificates by incrementing a number.
     */
    public SpecialMoneyRepository.AttachmentLocation resolveAttachmentForDownload(
            long attachmentId, UserPrincipal user) {
        SpecialMoneyRepository.AttachmentLocation location = repository.findAttachmentLocation(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเอกสารนี้"));
        SpecialMoneyRequestDto owningRequest = requireRequest(location.requestId());
        if (!canAccessEmployee(user, owningRequest.employeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return location;
    }

    /**
     * The single approval gate for welfare. A ฝ่าย manager gets no say here — they may file for
     * their team and see their team's requests, but only the CEO decides.
     */
    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "คำขอสวัสดิการทุกประเภทต้องได้รับการพิจารณาจาก CEO เท่านั้น");
        }
    }

    private boolean canAccessEmployee(UserPrincipal user, long employeeId) {
        return canViewAll(user)
            || (user.employeeId() != null && user.employeeId() == employeeId)
            || managesEmployee(employeeId, user);
    }

    /**
     * True when {@code user} is a ฝ่าย manager sharing the employee's division (excluding self).
     *
     * <p><b>This grants no approval rights</b> — welfare is CEO-only. It gates two lesser things:
     * filing a request on a team member's behalf ({@code resolveTargetEmployee}) and seeing a team
     * member's requests and quota ({@code canAccessEmployee}).
     *
     * <p>{@code reports_to_employee_id} is deliberately not consulted; it used to be, and was
     * dropped on the owner's instruction so this matches {@code AttendanceService.resolveScope},
     * which has always been division-only. HR is not special-cased either: it gets no
     * manager-shaped access to file on someone else's behalf.
     */
    private boolean managesEmployee(long employeeId, UserPrincipal user) {
        if (user == null || user.employeeId() == null) {
            return false;
        }
        return repository.findEmployeeAccess(employeeId)
            .map(access -> user.manager()
                && user.divisionId() != null
                && user.divisionId().equals(access.divisionId())
                && employeeId != user.employeeId())
            .orElse(false);
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    /**
     * Goes to the CEO, not to the requester's ผู้จัดการ. Notifying the manager would be worse than
     * useless now that they cannot act on it — it would put a request in front of someone whose
     * only possible response is to wait for someone else.
     */
    private void notifySubmitted(SpecialMoneyRequestDto request) {
        String title = "ส่งคำขอเงินสวัสดิการแล้ว";
        String message = "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกส่งให้ CEO พิจารณาแล้ว";
        notificationService.notify(request.employeeId(), "SPECIAL_MONEY_SUBMITTED", title, message, "/employee-requests", true);
        for (Long ceoEmployeeId : ceoApprovers.findEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "SPECIAL_MONEY_PENDING_CEO",
                "มีคำขอเงินสวัสดิการรอ CEO อนุมัติ",
                request.employeeName() + " ส่งคำขอ " + request.requestType() + " วันที่ " + request.eventDate(),
                "/employee-requests",
                true
            );
        }
    }

    private void notifyCeoApproved(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_APPROVED",
            "CEO อนุมัติคำขอเงินสวัสดิการแล้ว",
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " อนุมัติครบถ้วนแล้ว",
            "/employee-requests",
            true
        );
        if (request.managerApprovedBy() != null) {
            notificationService.notify(
                request.managerApprovedBy(),
                "SPECIAL_MONEY_APPROVED",
                "CEO อนุมัติคำขอเงินสวัสดิการแล้ว",
                request.employeeName() + " ได้รับการอนุมัติคำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ครบถ้วนแล้ว",
                "/employee-requests",
                true
            );
        }
    }

    /**
     * Notification coverage gap C: a rejection used to tell only the requester. Every LIVE welfare
     * request goes SUBMITTED -> CEO directly (see the class Javadoc), so {@code managerApprovedBy()}
     * is null for it and there is nobody upstream to tell. It is non-null ONLY for a legacy row
     * written before the manager stage was removed, rejected via {@code ceoRejectFrom(MANAGER_APPROVED,
     * ...)} -- that manager approved it under the old rules and should still hear that CEO closed it
     * as a rejection, the same counterpart-notify {@link #notifyCeoApproved} already applies to the
     * approve path.
     *
     * <p><b>S-6 (review, second pass):</b> like {@code OvertimeService#notifyRejected}, this had no
     * actor self-skip -- the one place the "nobody is notified about their own action" rule wasn't
     * applied. Reachable for BOTH recipients: welfare allows self-submission, so a CEO who filed
     * their own SUBMITTED request and then rejects it themselves would be told about their own
     * rejection; and a legacy row's {@code managerApprovedBy} can coincide with the current CEO
     * actor the same way {@code OvertimeService}'s does. {@code actorEmployeeId} is threaded through
     * and both recipients are skipped when they are the actor.
     */
    private void notifyRejected(SpecialMoneyRequestDto request, long actorEmployeeId) {
        if (request.employeeId() != actorEmployeeId) {
            notificationService.notify(
                request.employeeId(),
                "SPECIAL_MONEY_REJECTED",
                "คำขอเงินสวัสดิการถูกปฏิเสธ",
                "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกปฏิเสธ: "
                    + (request.reviewerNote() == null ? "กรุณาติดต่อผู้จัดการหรือ HR" : request.reviewerNote()),
                "/employee-requests",
                true
            );
        }
        if (request.managerApprovedBy() != null && request.managerApprovedBy() != actorEmployeeId) {
            notificationService.notify(
                request.managerApprovedBy(),
                "SPECIAL_MONEY_REJECTED",
                "CEO ปฏิเสธคำขอเงินสวัสดิการที่ผู้จัดการอนุมัติแล้ว",
                request.employeeName() + " มีคำขอ " + request.requestType() + " วันที่ " + request.eventDate()
                    + " ที่ผู้จัดการอนุมัติแล้ว แต่ถูก CEO ปฏิเสธ: "
                    + (request.reviewerNote() == null ? "กรุณาติดต่อ HR" : request.reviewerNote()),
                "/employee-requests",
                true
            );
        }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /**
     * The calendar year an annual welfare cap (MEDICAL's ฿ balance, UNIFORM_ANNUAL's once-a-year
     * count) is evaluated against.
     *
     * <p><b>Deliberately never {@code request.eventDate()}'s year.</b> {@code event_date} is
     * employee-supplied and unbounded -- {@link SubmitSpecialMoneyHttpRequest} marks it {@code
     * @NotNull} only, {@code V66} has no future-date constraint, and {@code evaluateMedical} does
     * not read it at all -- so a cap keyed on it can always be defeated by picking a date in a year
     * nothing has been approved against yet (e.g. filing today against next year's date, where the
     * "used so far" query always comes back ฿0). This was exploitable: see the fix commit for the
     * concrete ฿6,000-against-a-฿3,000-cap repro.
     *
     * <p>The money itself lands in payroll via {@code payrollMonth} (computed in {@link
     * #ceoApproveFrom}; V128's {@code welfare_pay} is summed by that same column), so that -- or,
     * before it exists, the best available estimate of it -- is what the cap has to track instead:
     *
     * <ul>
     *   <li>at <b>submit</b> time there is no {@code payrollMonth} yet (assigned only on approval),
     *       so the caller passes {@code today}: the year a reasonably prompt approval would almost
     *       always land in, and the only year-shaped value available this early. This is a
     *       fast-fail estimate, not the authoritative check -- see the next point.
     *   <li>at <b>approval</b> time ({@link #ceoApproveFrom}) the caller passes the {@code
     *       payrollMonth} just computed for THIS row -- the authoritative answer, because it is the
     *       exact year {@link SpecialMoneyRepository#findUsage}'s own money query files THIS
     *       approval's amount under once it is written.
     * </ul>
     *
     * <p>Backdating (a receipt from last month, filed today) is unaffected on purpose: this method
     * never looks at how far in the past the request's own dates are, only at when the cap check
     * itself is running. A genuinely backdated claim draws down the budget for the year it is
     * actually decided in -- the same year its money is actually paid -- which is correct, not a
     * side effect to route around.
     */
    private int usageYear(LocalDate reference) {
        return reference.getYear();
    }

    private SpecialMoneyRequestDto requireRequest(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอเงินพิเศษนี้"));
    }

    private Long requireEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        return user.employeeId();
    }

    private SpecialMoneyStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpecialMoneyStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะคำขอเงินพิเศษไม่ถูกต้อง");
        }
    }

    private SpecialMoneyType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเภทคำขอ");
        }
        try {
            return SpecialMoneyType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทคำขอเงินพิเศษไม่ถูกต้อง");
        }
    }

    private String note(ReviewSpecialMoneyRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
