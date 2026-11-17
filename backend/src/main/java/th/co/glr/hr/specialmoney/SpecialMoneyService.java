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
 * business rule, and it is the one way this class deliberately does NOT mirror
 * {@code th.co.glr.hr.overtime.OvertimeService}, which keeps a manager → CEO pipeline wherever the
 * employee's ฝ่าย has a ผู้จัดการ.
 *
 * <p>Note this is stricter than the signed 2018 welfare policy, which lets a driver or loader claim
 * travel per-diem straight from หัวหน้าฝ่าย. The stricter rule is intentional.
 *
 * <p>{@code MANAGER_APPROVED} survives in {@link SpecialMoneyStatus} and in {@code chk_smr_status}
 * only for rows written before the manager stage was removed; nothing can enter that state now.
 *
 * <p><b>A ฝ่าย manager has no welfare access of any kind.</b> Welfare is
 * confidential to each employee: {@code hr} and {@code ceo} see everything, and <em>everyone else,
 * a ผู้จัดการ included, sees only their own rows</em>. There is deliberately no
 * {@code managesEmployee} concept in this class: no division-wide read and no
 * submit-on-behalf:
 *
 * <ul>
 *   <li><b>Read</b> — {@link #list}, {@link #usage}, {@link #listAttachments} and
 *       {@link #resolveAttachmentForDownload} all funnel through {@link #canAccessEmployee}, which
 *       is "view-all role, or your own employee id", full stop.
 *   <li><b>Submit-on-behalf</b> — not offered alongside the read scope. Filing a
 *       welfare claim means supplying the event date, the reason and the type-specific
 *       {@code detail} (a death in the family, a wedding, a medical event); that IS the
 *       confidential content, so "may file for you but may not read it" is not a coherent
 *       boundary. It would also leave a write-only limbo: the filer could create a row
 *       and then neither list it, read its evidence, nor see its outcome.
 *   <li><b>{@code requested_by_id}</b> — {@link #cancel} and {@link #requireCanAttach} do not
 *       honour "whoever filed it". With no on-behalf filing that disjunct could only ever match a
 *       legacy row, where it would let a manager cancel (and be handed the full DTO back) or slip
 *       documents into a colleague's claim. The employee themselves keeps both rights.
 * </ul>
 *
 * <p>Nothing is stranded by that last point: every row's own employee can still cancel and attach.
 */
@Service
public class SpecialMoneyService {
    /** Package-private so tests derive "today" from the same zone rather than the JVM default. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
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
        Long ownEmployeeId = null;
        if (!canViewAll(user)) {
            // The caller's OWN employee id, and nothing else. Passing a ผู้จัดการ's ฝ่าย would hand
            // every manager a division-wide read of their team's welfare claims -- medical,
            // funeral, wedding. Welfare is confidential per employee, so the only non-hr/ceo
            // scope is self.
            ownEmployeeId = requireEmployeeId(user);
            if (requestedEmployeeId != null && !canAccessEmployee(user, requestedEmployeeId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }

        return repository.findRequests(new SpecialMoneyFilter(
            employeeId,
            ownEmployeeId,
            effectiveFrom,
            effectiveTo,
            parseStatus(requestedStatus),
            requestType
        ));
    }

    /** The submit form's employee picker. Non-hr/ceo callers get exactly one entry — themselves — because
     * #resolveTargetEmployee will refuse anyone else. hr/ceo still get the full roster, which for them is a list
     * filter, not an on-behalf picker: they cannot submit for an arbitrary employee either. */
    public List<SpecialMoneyEmployeeOption> employeeOptions(UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        return repository.findEmployeeOptions(actorEmployeeId, canViewAll(user));
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
        // The snapshot has always carried the per-year count for the once-per-year uniform rule; it was simply dropped
        // here, so the UI could not warn "you already filed this year" and the employee only found out from the 400 on
        // submit.
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
            // Legacy rows only. Welfare no longer has a manager stage (see the class Javadoc), so nothing new can enter
            // this state -- but rows parked here before the change still need a way out, and it is the same CEO decision
            // either way.
            return ceoApproveFrom(SpecialMoneyStatus.MANAGER_APPROVED, id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
    }

    /** Read-only preview of the ceiling #ceoApproveFrom would enforce for this request IF EVALUATED RIGHT NOW, so
     * the CEO's approve dialog can show it before submitting a decision. Nor does it write an audit row -- nothing
     * changed. */
    public SpecialMoneyApprovalPreviewDto approvalPreview(long id, UserPrincipal user) {
        SpecialMoneyRequestDto existing = requireRequest(id);
        requireCeo(user);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status != SpecialMoneyStatus.SUBMITTED && status != SpecialMoneyStatus.MANAGER_APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
        }

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        ApprovalCeiling ceiling = computeApprovalCeiling(existing, today);
        return new SpecialMoneyApprovalPreviewDto(
            existing.requestedAmount(), ceiling.eligibleAmount(), ceiling.payrollMonth());
    }

    /** The CEO's approval — the only approval welfare has. from == MANAGER_APPROVED exists only to clear rows
     * written before the manager stage was removed. from == SUBMITTED is the live route for every request. */
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

        // approvedOn shares `today` rather than making a second `LocalDate.now(BUSINESS_ZONE)` call:
        // both name the same instant and a request evaluated
        // across a real midnight boundary should not see two different "todays". `today` is also fed
        // into computeApprovalCeiling below, so the ceiling preview (GET /approval-preview) and this
        // approval always evaluate against the same "now".
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        ApprovalCeiling ceiling = computeApprovalCeiling(existing, today);
        LocalDate payrollMonth = ceiling.payrollMonth();

        boolean exceedsCap = approvedAmount.compareTo(ceiling.eligibleAmount()) > 0;
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

    /** The payroll month and policy ceiling for an EXISTING request, evaluated as of today. This is
     * payroll-adjacent money code: the refactor must not change what either caller observes. */
    private ApprovalCeiling computeApprovalCeiling(SpecialMoneyRequestDto existing, LocalDate today) {
        SpecialMoneyType type = parseType(existing.requestType());

        // The 25th-of-month payroll cutoff. Rolling forward past an already-PROCESSED month is deliberate: payroll
        // writes a processed period once, so a request landing in a closed month would be approved and then never
        // paid.
        int cutoffDay = appProperties.getSpecialMoney().getPayrollCutoffDay();
        LocalDate payrollMonth = today.getDayOfMonth() <= cutoffDay
            ? today.withDayOfMonth(1)
            : today.plusMonths(1).withDayOfMonth(1);
        while (repository.payrollMonthProcessed(payrollMonth)) {
            payrollMonth = payrollMonth.plusMonths(1);
        }

        EmployeeEligibilitySnapshot eligibility = repository.findEligibility(existing.employeeId(), today)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน"));
        // The AUTHORITATIVE cap-year check (see usageYear's Javadoc): the year this approval's own payrollMonth falls
        // in, never existing.eventDate()'s year.
        UsageSnapshot usage = repository.findUsage(existing.employeeId(), usageYear(payrollMonth));
        PolicyAmounts amounts = repository.findPolicyAmounts(type.name(), today);
        Set<String> excludedProvinces = repository.findExcludedProvinces();

        // Re-run the evaluator to learn the policy ceiling for this type/employee.
        //
        // The recheck is built from what the EMPLOYEE asked for, not from any CEO-chosen amount.
        // Substituting a CEO-supplied figure would make the
        // cap guard structurally dead for every uncapped type: UNIFORM_NEW_STAFF, TRAVEL_LODGING,
        // TRAINING and OTHER all return `requestedAmount` as their eligible amount, so feeding in the
        // approved amount would make eligibleAmount == approvedAmount and the comparison could never be
        // true. A CEO could approve ฿50,000 against a ฿5,000 request and never be asked why.
        //
        // Reading from the original request makes the ceiling mean "the cap, or failing a cap, what
        // was actually requested" -- so approving MORE than the policy allows, or more than the
        // employee asked for, both require a written reason.
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

        return new ApprovalCeiling(payrollMonth, recheck.eligibleAmount());
    }

    /** #computeApprovalCeiling's result: the payroll month a fresh approval would land in right now, and the policy
     * ceiling it would be checked against. */
    private record ApprovalCeiling(LocalDate payrollMonth, BigDecimal eligibleAmount) {
    }

    @Transactional
    public SpecialMoneyRequestDto reject(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);
        SpecialMoneyStatus status = parseStatus(existing.status());
        if (status == SpecialMoneyStatus.SUBMITTED) {
            // Symmetric with approve(): the sole reviewer must be able to refuse as well as accept, or a request could
            // only ever be approved.
            return ceoRejectFrom(SpecialMoneyStatus.SUBMITTED, id, request, user, actorEmployeeId, existing);
        }
        if (status == SpecialMoneyStatus.MANAGER_APPROVED) {
            return ceoRejectFrom(SpecialMoneyStatus.MANAGER_APPROVED, id, request, user, actorEmployeeId, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว");
    }

    /** The CEO's rejection. As with #ceoApproveFrom, MANAGER_APPROVED is reachable only for rows written before
     * welfare's manager stage was removed. */
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
        notifyRejected(after);
        return after;
    }

    @Transactional
    public SpecialMoneyRequestDto cancel(long id, ReviewSpecialMoneyRequest request, UserPrincipal user) {
        SpecialMoneyRequestDto existing = requireRequest(id);
        Long actorEmployeeId = requireEmployeeId(user);
        // The employee whose claim it is, and nobody else.
        if (existing.employeeId() != actorEmployeeId) {
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
        return after;
    }

    // Gates

    /**
     * Welfare is filed for yourself, by yourself — <b>every role, no exception</b>. A ฝ่าย manager
     * cannot file for a team member, because the request body itself (event date, reason,
     * {@code detail}) is the confidential content. HR and the CEO cannot file on someone's
     * behalf either.
     */
    private long resolveTargetEmployee(Long requestedEmployeeId, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        long targetEmployeeId = requestedEmployeeId == null ? actorEmployeeId : requestedEmployeeId;
        if (targetEmployeeId != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "พนักงานสามารถยื่นคำขอเงินพิเศษให้ตนเองเท่านั้น");
        }
        return targetEmployeeId;
    }

    private void validateEmployee(long employeeId) {
        if (!repository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน");
        }
    }

    /** Refuses to approve an evidence-required type with nothing attached. The type-specific requirement is
     * surfaced to the uploader in the UI and is the reviewer's job to check — this gate only guarantees there is
     * something to check. */
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

    // Evidence attachments

    /** Attaches a piece of evidence. Only the employee the claim belongs to may upload, and only while the request
     * is still SUBMITTED — once a decision is recorded the evidence it was based on must not change underneath it. */
    public void requireCanAttach(long id, UserPrincipal user) {
        Long actorEmployeeId = requireEmployeeId(user);
        SpecialMoneyRequestDto existing = requireRequest(id);

        if (existing.employeeId() != actorEmployeeId) {
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

    /** Resolves an attachment for download, authorizing against its OWNING REQUEST rather than the attachment id.
     * An attachment id is guessable; without this the file store would be a way to read other people's medical
     * receipts and death certificates by incrementing a number. */
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
     * The single approval gate for welfare: only the CEO decides. A ฝ่าย manager gets no say here
     * and no read of a team member's welfare either (see the class Javadoc).
     */
    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "คำขอสวัสดิการทุกประเภทต้องได้รับการพิจารณาจาก CEO เท่านั้น");
        }
    }

    /**
     * The one read gate for welfare, shared by {@link #usage}, {@link #listAttachments},
     * {@link #resolveAttachmentForDownload} and {@link #list}'s {@code employeeId} filter.
     *
     * <p><b>Two disjuncts only: a view-all role, or your own row.</b> There is deliberately no
     * manager branch: a {@code || managesEmployee(employeeId, user)} disjunct would let
     * a ฝ่าย manager list their whole division's welfare claims, read the per-type amounts
     * those employees had drawn down, and download the evidence behind them — death certificates,
     * medical receipts. Welfare is confidential to each employee; only hr/ceo look across it.
     *
     * <p>Unlike {@code OvertimeService} and {@code AttendanceService}, which are division-scoped by
     * design, this method must stay two-disjunct. Re-adding a manager branch here silently reopens
     * every read path above at once — {@code SpecialMoneyScopeIntegrationTest} pins all four.
     */
    private boolean canAccessEmployee(UserPrincipal user, long employeeId) {
        return canViewAll(user)
            || (user.employeeId() != null && user.employeeId() == employeeId);
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && VIEW_ALL_ROLES.contains(user.role());
    }

    // Notifications

    /** Goes to the CEO, not to the requester's ผู้จัดการ. Notifying the manager would be worse than useless now
     * that they cannot act on it — it would put a request in front of someone whose only possible response is to
     * wait for someone else. */
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

    /**
     * Goes to the employee and to nobody else — the same rule as {@link #notifySubmitted}.
     *
     * <p>It must not also notify {@code managerApprovedBy} on a row that carries one: the body
     * names the employee, the welfare type and the event date. A notification is delivered to
     * one {@code employee_id} and read back own-only ({@code NotificationRepository#findByEmployeeId}),
     * so that would push confidential welfare content straight into a ผู้จัดการ's own inbox —
     * the very thing {@link #canAccessEmployee} refuses them through every query path. It could
     * only ever fire on a legacy row ({@code ceoDirectApprove} never stamps the manager columns, and
     * nothing can enter {@code MANAGER_APPROVED}), and it would link to a screen where that
     * manager cannot see the request at all, so it would be a dead-end leak.
     */
    private void notifyCeoApproved(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_APPROVED",
            "CEO อนุมัติคำขอเงินสวัสดิการแล้ว",
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " อนุมัติครบถ้วนแล้ว",
            "/employee-requests",
            true
        );
    }

    private void notifyRejected(SpecialMoneyRequestDto request) {
        notificationService.notify(
            request.employeeId(),
            "SPECIAL_MONEY_REJECTED",
            "คำขอเงินสวัสดิการถูกปฏิเสธ",
            // "ติดต่อฝ่ายบุคคล", not the old "ติดต่อผู้จัดการหรือ HR": a ฝ่าย manager can no longer see this request, so
            // pointing the employee at them would send them to someone with nothing to look up. HR and the CEO are the
            // only roles who can still read the row.
            "คำขอ " + request.requestType() + " วันที่ " + request.eventDate() + " ถูกปฏิเสธ: "
                + (request.reviewerNote() == null ? "กรุณาติดต่อฝ่ายบุคคล" : request.reviewerNote()),
            "/employee-requests",
            true
        );
    }

    // Small helpers

    /** The calendar year an annual welfare cap (MEDICAL's ฿ balance, UNIFORM_ANNUAL's once-a-year count) is
     * evaluated against. Deliberately never request.eventDate()'s year. This was exploitable: see the fix commit
     * for the concrete ฿6,000-against-a-฿3,000-cap repro. */
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
