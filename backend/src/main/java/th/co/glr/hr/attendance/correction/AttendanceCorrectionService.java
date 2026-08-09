package th.co.glr.hr.attendance.correction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;

/**
 * An employee who missed a clock-in/clock-out scan files a request here to record the correct
 * time; the CEO approves or rejects it. Modelled on {@code LeaveService}/{@code OvertimeService}'s
 * submit/approve/reject/cancel shape, deliberately simpler in one respect: there is <b>no manager
 * stage</b> — the CEO is the sole approver (product decision, no ฝ่าย manager routing at all, unlike
 * overtime's two-stage manager→CEO flow). See {@link #requireCeo}.
 *
 * <p>Approving does not just flip a status column: it is the one write path in this codebase (V7
 * reserved {@code punch_source = 'MANUAL'} / {@code ingest_method = 'MANUAL_ENTRY'} for exactly
 * this, and nothing wrote them before this class) that inserts a real
 * {@code hr.attendance_punch} row and marks the affected {@code hr.attendance_daily} row
 * {@code is_manual_override = TRUE} via {@link AttendanceDailyService#applyManualCorrection} — see
 * that method's javadoc for why the derivation itself (total/late/early minutes) is the SAME §76
 * reporting-only computation an ordinary punch would produce, not a new one. Rejecting never writes
 * to attendance_punch/attendance_daily at all.
 */
@Service
public class AttendanceCorrectionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final AttendanceCorrectionRepository repository;
    private final AttendanceDailyService dailyService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AttendanceCorrectionService(
            AttendanceCorrectionRepository repository,
            AttendanceDailyService dailyService,
            AuditService auditService,
            NotificationService notificationService) {
        this.repository = repository;
        this.dailyService = dailyService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    public List<AttendanceCorrectionRequestDto> list(UserPrincipal user, Long requestedEmployeeId, String requestedStatus) {
        AttendanceCorrectionStatus status = parseStatus(requestedStatus);
        boolean viewAll = canViewAll(user);
        Long employeeId = requestedEmployeeId;
        if (!viewAll) {
            long actorEmployeeId = requireEmployeeId(user);
            if (requestedEmployeeId != null && requestedEmployeeId != actorEmployeeId) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            employeeId = actorEmployeeId;
        }
        return repository.findRequests(new AttendanceCorrectionFilter(employeeId, viewAll, status))
            .stream()
            .map(dto -> withCanReviewFlag(dto, user))
            .toList();
    }

    @Transactional
    public AttendanceCorrectionRequestDto submit(SubmitAttendanceCorrectionRequest request, UserPrincipal user) {
        long employeeId = requireEmployeeId(user);
        validateEmployee(employeeId);
        validateRequestShape(request);
        validateWorkDate(request.workDate());

        // Friendly pre-check ahead of the DB-level once-per-open-request guard (see the
        // ux_attendance_correction_one_open_per_employee_day index and the DuplicateKeyException
        // catch below for the race backstop).
        if (repository.hasOpenRequest(employeeId, request.workDate())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีคำขอแก้ไขเวลาสำหรับวันนี้ที่ยังไม่ได้รับการพิจารณาอยู่แล้ว");
        }

        long id;
        try {
            id = repository.create(employeeId, employeeId, request);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "มีคำขอแก้ไขเวลาสำหรับวันนี้ที่ยังไม่ได้รับการพิจารณาอยู่แล้ว");
        }
        AttendanceCorrectionRequestDto created = requireRequest(id);
        auditService.record(user, "SUBMIT_ATTENDANCE_CORRECTION_REQUEST", "attendance_correction_request", id, null, created);
        notifyCeoOfSubmission(created);
        return withCanReviewFlag(created, user);
    }

    @Transactional
    public AttendanceCorrectionRequestDto approve(long id, ReviewAttendanceCorrectionRequest request, UserPrincipal user) {
        long actorEmployeeId = requireEmployeeId(user);
        requireCeo(user);
        AttendanceCorrectionRequestDto existing = requireRequest(id);
        requireStatus(existing, AttendanceCorrectionStatus.SUBMITTED);

        applyCorrectionToAttendance(existing);

        int updated = repository.approve(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        AttendanceCorrectionRequestDto after = requireRequest(id);
        auditService.record(user, "APPROVE_ATTENDANCE_CORRECTION_REQUEST", "attendance_correction_request", id, existing, after);
        notificationService.notify(
            after.employeeId(),
            "ATTENDANCE_CORRECTION_APPROVED",
            "คำขอแก้ไขเวลาเข้า-ออกงานได้รับการอนุมัติ",
            "คำขอแก้ไขเวลาเข้า-ออกงานวันที่ " + after.workDate() + " ได้รับการอนุมัติแล้ว",
            "/attendance",
            true);
        return withCanReviewFlag(after, user);
    }

    /**
     * Inserts the corrected punch(es) and re-derives the day's roll-up as an authoritative override.
     * Runs inside {@link #approve}'s own transaction so a failure here (e.g. the day somehow ends up
     * with no punches) rolls the status flip back too — never a request left APPROVED with nothing
     * actually corrected on the attendance side.
     */
    private void applyCorrectionToAttendance(AttendanceCorrectionRequestDto existing) {
        long employeeId = existing.employeeId();
        LocalDate workDate = existing.workDate();
        String siteCode = repository.resolveSiteCode(employeeId, workDate);
        String badgeCode = repository.resolveBadgeCode(employeeId);

        AttendanceCorrectionType type = AttendanceCorrectionType.valueOf(existing.correctionType());
        if (type == AttendanceCorrectionType.CHECK_IN || type == AttendanceCorrectionType.BOTH) {
            repository.insertManualPunch(employeeId, siteCode, badgeCode, existing.requestedCheckIn(), workDate);
        }
        if (type == AttendanceCorrectionType.CHECK_OUT || type == AttendanceCorrectionType.BOTH) {
            repository.insertManualPunch(employeeId, siteCode, badgeCode, existing.requestedCheckOut(), workDate);
        }
        dailyService.applyManualCorrection(new EmployeeDay(employeeId, workDate));
    }

    @Transactional
    public AttendanceCorrectionRequestDto reject(long id, ReviewAttendanceCorrectionRequest request, UserPrincipal user) {
        long actorEmployeeId = requireEmployeeId(user);
        requireCeo(user);
        AttendanceCorrectionRequestDto existing = requireRequest(id);
        requireStatus(existing, AttendanceCorrectionStatus.SUBMITTED);

        int updated = repository.reject(id, actorEmployeeId, note(request));
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
        AttendanceCorrectionRequestDto after = requireRequest(id);
        auditService.record(user, "REJECT_ATTENDANCE_CORRECTION_REQUEST", "attendance_correction_request", id, existing, after);
        notificationService.notify(
            after.employeeId(),
            "ATTENDANCE_CORRECTION_REJECTED",
            "คำขอแก้ไขเวลาเข้า-ออกงานถูกปฏิเสธ",
            "คำขอแก้ไขเวลาเข้า-ออกงานวันที่ " + after.workDate() + " ถูกปฏิเสธ: "
                + (after.reviewerNote() == null ? "กรุณาติดต่อฝ่ายบุคคล" : after.reviewerNote()),
            "/attendance",
            true);
        return withCanReviewFlag(after, user);
    }

    @Transactional
    public AttendanceCorrectionRequestDto cancel(long id, UserPrincipal user) {
        AttendanceCorrectionRequestDto existing = requireRequest(id);
        long actorEmployeeId = requireEmployeeId(user);
        // Requester-only: unlike leave/overtime, there is no reviewer-side cancel here — the CEO's
        // only actions on an open request are approve/reject (see class javadoc: no manager stage,
        // and cancellation-of-someone-else's-request was never asked for in the product spec this
        // feature was built from).
        if (existing.employeeId() != actorEmployeeId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        requireStatus(existing, AttendanceCorrectionStatus.SUBMITTED);

        int updated = repository.cancel(id);
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขเวลานี้ไม่สามารถยกเลิกได้แล้ว");
        }
        AttendanceCorrectionRequestDto after = requireRequest(id);
        auditService.record(user, "CANCEL_ATTENDANCE_CORRECTION_REQUEST", "attendance_correction_request", id, existing, after);
        return withCanReviewFlag(after, user);
    }

    // --- validation -----------------------------------------------------------------------------

    private void validateEmployee(long employeeId) {
        if (!repository.employeeExists(employeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบข้อมูลพนักงาน");
        }
    }

    /**
     * Mirrors {@code chk_attendance_correction_fields_match_type} in Java, ahead of the DB — a
     * request whose {@code requestedCheckIn}/{@code requestedCheckOut} do not match
     * {@code correctionType} must fail with a clear 400 <em>before</em> it ever reaches the insert,
     * not surface as an opaque constraint-violation 500. See V135's migration comment for the exact
     * constraint this must stay in lockstep with.
     */
    private void validateRequestShape(SubmitAttendanceCorrectionRequest request) {
        AttendanceCorrectionType type = request.correctionType();
        OffsetDateTime checkIn = request.requestedCheckIn();
        OffsetDateTime checkOut = request.requestedCheckOut();
        switch (type) {
            case CHECK_IN -> {
                if (checkIn == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุเวลาเข้างานที่ถูกต้อง");
                }
                if (checkOut != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "คำขอแก้ไขเฉพาะเวลาเข้างานต้องไม่ระบุเวลาออกงาน");
                }
            }
            case CHECK_OUT -> {
                if (checkOut == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุเวลาออกงานที่ถูกต้อง");
                }
                if (checkIn != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "คำขอแก้ไขเฉพาะเวลาออกงานต้องไม่ระบุเวลาเข้างาน");
                }
            }
            case BOTH -> {
                if (checkIn == null || checkOut == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุทั้งเวลาเข้างานและเวลาออกงาน");
                }
            }
        }
        if (checkIn != null && checkOut != null && checkOut.isBefore(checkIn)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาออกงานต้องไม่อยู่ก่อนเวลาเข้างาน");
        }
        LocalDate workDate = request.workDate();
        if (checkIn != null && !checkIn.atZoneSameInstant(BUSINESS_ZONE).toLocalDate().equals(workDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาเข้างานต้องตรงกับวันที่ขอแก้ไข");
        }
        if (checkOut != null && !checkOut.atZoneSameInstant(BUSINESS_ZONE).toLocalDate().equals(workDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เวลาออกงานต้องตรงกับวันที่ขอแก้ไข");
        }
    }

    private void validateWorkDate(LocalDate workDate) {
        if (workDate.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่สามารถขอแก้ไขเวลาสำหรับวันที่ในอนาคตได้");
        }
    }

    // --- access control ---------------------------------------------------------------------------

    /**
     * CEO-only, no manager stage — the deliberate simplification from overtime's two-stage
     * manager→CEO routing (product decision for this feature). Mirrors
     * {@code OvertimeService#requireCeo} exactly in shape: {@code "ceo".equals(user.role())}, else a
     * 403 {@link ApiException}.
     */
    private void requireCeo(UserPrincipal user) {
        if (user == null || !"ceo".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้นที่สามารถพิจารณาคำขอแก้ไขเวลาเข้า-ออกงานได้");
        }
    }

    private boolean canViewAll(UserPrincipal user) {
        return user != null && "ceo".equals(user.role());
    }

    private AttendanceCorrectionRequestDto withCanReviewFlag(AttendanceCorrectionRequestDto dto, UserPrincipal user) {
        boolean canReview = canViewAll(user) && "SUBMITTED".equals(dto.status());
        return new AttendanceCorrectionRequestDto(
            dto.id(), dto.employeeId(), dto.employeeCode(), dto.employeeName(), dto.workDate(),
            dto.correctionType(), dto.requestedCheckIn(), dto.requestedCheckOut(), dto.reason(), dto.status(),
            dto.requestedById(), dto.requestedByName(), dto.requestedAt(),
            dto.reviewedById(), dto.reviewedByName(), dto.reviewedAt(), dto.reviewerNote(), dto.cancelledAt(),
            dto.createdAt(), dto.updatedAt(), canReview);
    }

    // --- helpers ------------------------------------------------------------------------------

    private long requireEmployeeId(UserPrincipal user) {
        if (user == null || user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        return user.employeeId();
    }

    private AttendanceCorrectionRequestDto requireRequest(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอแก้ไขเวลานี้"));
    }

    private void requireStatus(AttendanceCorrectionRequestDto request, AttendanceCorrectionStatus status) {
        if (!status.name().equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอแก้ไขเวลานี้ได้รับการพิจารณาไปแล้ว");
        }
    }

    private AttendanceCorrectionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AttendanceCorrectionStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะคำขอไม่ถูกต้อง");
        }
    }

    private String note(ReviewAttendanceCorrectionRequest request) {
        return request == null ? null : request.reviewerNote();
    }

    /**
     * The notification link tracks where this feature's review UI actually lives, not a fixed API
     * route -- it moved from {@code /employee-requests} (the old third tab on the combined requests
     * page) to {@code /attendance} (fix/attendance-correction-on-attendance-page), matching the
     * frontend move of both {@code AttendanceCorrectionPanel} and the submit modal onto that page.
     * Same link is used by {@link #approve}/{@link #reject}'s own notifications to the employee.
     * The next person who relocates this UI again must update this literal (all three call sites)
     * to match, or every notification/deep-link into this feature silently 404s-by-content (lands
     * on a page with nothing about attendance correction on it) instead of erroring.
     */
    private void notifyCeoOfSubmission(AttendanceCorrectionRequestDto created) {
        for (long ceoEmployeeId : repository.findCeoApproverEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "ATTENDANCE_CORRECTION_SUBMITTED",
                "มีคำขอแก้ไขเวลาเข้า-ออกงานใหม่",
                created.employeeName() + " ยื่นคำขอแก้ไขเวลาเข้า-ออกงานวันที่ " + created.workDate(),
                "/attendance",
                true);
        }
    }
}
