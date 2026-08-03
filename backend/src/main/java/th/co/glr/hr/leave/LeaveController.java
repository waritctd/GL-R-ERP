package th.co.glr.hr.leave;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
// Both resource kinds now serve from Postgres: ByteArrayResource for the leave POLICY DOCUMENT
// (V133) and, as of V132, for ATTACHMENTS too (hr.file_attachment_blob) -- FileSystemResource only
// remains live here for pre-V132 DISK_LEGACY attachment rows whose bytes still resolve on disk. See
// #downloadAttachment below for the storage_state branch.
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.leave.LeaveResponses.LeaveBalancesResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveCalendarContextResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveContactDefaultsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveDetailResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveEmployeeOptionsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveListResponse;
import th.co.glr.hr.leave.LeaveResponses.LeavePreviewResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveReviewSummaryResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveTypesResponse;

@RestController
@RequestMapping("/api/leave")
public class LeaveController {
    // A ~70KB real-world PDF (the owner's actual §5 announcement file) with generous headroom --
    // not a tight bound, just enough to refuse something wildly wrong (a misdirected upload, an
    // accidental non-PDF renamed to .pdf that happens to pass the content-type check) before it
    // reaches the database as a BYTEA row.
    private static final long MAX_POLICY_DOCUMENT_BYTES = 10L * 1024 * 1024;

    private final LeaveService leaveService;
    private final SessionContext sessions;
    private final LeavePolicyDocumentRepository leavePolicyDocuments;
    private final LeaveCalendarContextService calendarContextService;
    private final FileAttachmentBlobRepository attachmentBlobs;

    public LeaveController(
            LeaveService leaveService,
            SessionContext sessions,
            LeavePolicyDocumentRepository leavePolicyDocuments,
            LeaveCalendarContextService calendarContextService,
            FileAttachmentBlobRepository attachmentBlobs) {
        this.leaveService = leaveService;
        this.sessions = sessions;
        this.leavePolicyDocuments = leavePolicyDocuments;
        this.calendarContextService = calendarContextService;
        this.attachmentBlobs = attachmentBlobs;
    }

    @GetMapping
    LeaveListResponse list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveListResponse(leaveService.list(user, fromDate, toDate, employeeId, status));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    LeaveDetailResponse submit(@Valid @RequestBody SubmitLeaveRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.submit(request, user));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LeaveDetailResponse submitMultipart(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam("leaveTypeCode") String leaveTypeCode,
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam("reason") String reason,
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(value = "contactHouseNo", required = false) String contactHouseNo,
            @RequestParam(value = "contactSubdistrict", required = false) String contactSubdistrict,
            @RequestParam(value = "contactDistrict", required = false) String contactDistrict,
            @RequestParam(value = "contactProvince", required = false) String contactProvince,
            @RequestParam(value = "contactPhone", required = false) String contactPhone,
            @RequestParam(value = "purposeCode", required = false) String purposeCode,
            @RequestParam(value = "requestedAsEmergency", required = false) Boolean requestedAsEmergency,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, leaveTypeCode, startDate, endDate, reason,
            startTime, endTime, contactHouseNo, contactSubdistrict, contactDistrict, contactProvince, contactPhone,
            purposeCode, requestedAsEmergency);
        return new LeaveDetailResponse(leaveService.submit(request, attachment, user));
    }

    @GetMapping("/employees")
    LeaveEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveEmployeeOptionsResponse(leaveService.employeeOptions(user));
    }

    @GetMapping("/types")
    LeaveTypesResponse leaveTypes(HttpSession session) {
        sessions.requireUser(session);
        return new LeaveTypesResponse(leaveService.leaveTypes());
    }

    /**
     * The §5 company-announcement PDF (rules tab). {@code sessions.requireUser} only -- every
     * employee may read the rules that bind them, same access as {@link #leaveTypes}. Unchanged by
     * the write endpoint below being added: this stays a read, not a write, authorization question.
     *
     * <p>404 with a clear Thai message whenever {@link LeavePolicyDocumentRepository#findCurrent}
     * has nothing to serve -- the common case on a fresh environment (an empty table) reads
     * identically to a real environment whose upload happens to be mid-flight; both are honestly
     * "nothing to serve right now". The frontend never lets a user reach a raw 404: it HEAD-probes
     * this same route first and renders a disabled, explained state when it 404s -- see
     * {@code RulesTab.jsx}'s {@code policyDocumentAvailable} query.
     *
     * <p>Also transparently answers {@code HEAD /api/leave/policy-document} -- Spring's
     * {@code DispatcherServlet} serves HEAD for any {@code @GetMapping} by running this same method
     * and dropping the body, so the frontend's availability probe needs no separate endpoint.
     */
    @GetMapping("/policy-document")
    ResponseEntity<Resource> policyDocument(HttpSession session) {
        sessions.requireUser(session);
        LeavePolicyDocumentDto current = leavePolicyDocuments.findCurrent()
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ยังไม่มีการอัปโหลดเอกสารประกาศฉบับนี้ กรุณาติดต่อฝ่ายบุคคล"));
        byte[] content = leavePolicyDocuments.findContent(current.documentId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ไม่พบเนื้อหาของไฟล์ประกาศ กรุณาติดต่อฝ่ายบุคคล"));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(current.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(current.fileName()).build().toString())
            .body(new ByteArrayResource(content));
    }

    /**
     * Uploads a new version of the §5 announcement PDF -- HR/CEO only ({@code requireAnyRole},
     * matching {@code HolidayController}'s write gate), since this is the one write path that puts
     * real content in front of every employee via {@link #policyDocument}. Never overwrites: each
     * call inserts a brand-new {@link LeavePolicyDocumentRepository#insert} row tied to the
     * {@code effectiveFrom} date the caller supplies, so an older announcement's document stays
     * retrievable by id even after a newer one supersedes it as "current" -- see V133's migration
     * comment.
     *
     * <p>This is a brand-new authorization surface, so it ships with a real-Postgres integration
     * test through this exact controller ({@code LeaveControllerPolicyDocumentIntegrationTest})
     * proving the wrong roles get 403 -- see CLAUDE.md "Permission changes must ship evidence".
     *
     * <p>Only {@code application/pdf} is accepted (the announcement is always a PDF; there is no
     * legitimate reason for this endpoint to accept anything else) and capped at
     * {@link #MAX_POLICY_DOCUMENT_BYTES}.
     */
    @PostMapping("/policy-document")
    Map<String, LeavePolicyDocumentDto> uploadPolicyDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("effectiveFrom") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "ceo");
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        }
        if (!MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รองรับเฉพาะไฟล์ PDF");
        }
        if (file.getSize() > MAX_POLICY_DOCUMENT_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์มีขนาดใหญ่เกินไป");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String fileName = file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()
            ? file.getOriginalFilename() : "leave-policy-announcement.pdf";
        LeavePolicyDocumentDto saved = leavePolicyDocuments.insert(
            fileName, file.getContentType(), content, effectiveFrom, user.employeeId());
        return Map.of("document", saved);
    }

    // Paper-form (ใบลาหยุด F-HR-020) autofill for the contact-during-leave block, plus read-only
    // position/department/division -- reuses the /balances access predicate (own record, or HR/CEO,
    // or the employee's direct manager). See LeaveService#contactDefaults.
    @GetMapping("/contact-defaults")
    LeaveContactDefaultsResponse contactDefaults(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveContactDefaultsResponse(leaveService.contactDefaults(user, employeeId));
    }

    /**
     * The CALLING employee's own holiday + resolved work-schedule context for {@code [from, to]}
     * -- so the leave composer can show why {@code LeaveDayMath}'s schedule/holiday-aware count
     * differs from the plain calendar span. {@code sessions.requireUser} only, same access as
     * {@link #leaveTypes}/{@link #policyDocument}: unlike {@code HolidayController}/
     * {@code WorkScheduleController} (hr/ceo only, admin surfaces this deliberately does not
     * widen -- see {@link LeaveCalendarContextService}'s javadoc), this is inherently self-scoped.
     * There is no {@code employeeId} request parameter: the employee id always comes from the
     * session, never from the caller, so there is no code path by which this can return anyone
     * else's schedule -- including for HR/CEO callers, who get their own data here same as anyone
     * else (an admin wanting someone ELSE's resolved schedule is what
     * {@code WorkScheduleController} is for, and that stays hr/ceo-gated, untouched).
     *
     * <p>{@code from}/{@code to} validated the same way as {@code HolidayController}'s admin GET
     * (both required, {@code to} not before {@code from}) -- see
     * {@link LeaveCalendarContextService#get}.
     */
    @GetMapping("/calendar-context")
    LeaveCalendarContextResponse calendarContext(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        long employeeId = requireOwnEmployeeId(user);
        return new LeaveCalendarContextResponse(calendarContextService.get(employeeId, from, to));
    }

    /** Same "account not linked to an employee record" message as {@code LeaveService#requireEmployeeId}. */
    private static long requireOwnEmployeeId(UserPrincipal user) {
        if (user.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        return user.employeeId();
    }

    @GetMapping("/balances")
    LeaveBalancesResponse balances(
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "year", required = false) Integer year,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveBalancesResponse(leaveService.balances(user, employeeId, year));
    }

    @PostMapping("/{id}/approve")
    LeaveDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    LeaveDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    LeaveDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewLeaveRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveDetailResponse(leaveService.cancel(id, request, user));
    }

    // Phase A0b dry-run: runs the identical gate chain #submit runs, against an uncommitted
    // request, writing nothing -- see LeaveService#preview for the FULL vs QUICK depth and the
    // nullable-dates contract.
    @PostMapping("/preview")
    LeavePreviewResponse preview(@Valid @RequestBody LeavePreviewRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeavePreviewResponse(leaveService.preview(request, user));
    }

    // Phase A0b: count of SUBMITTED requests THIS actor may act on -- see
    // LeaveService#reviewSummary for why this is canReviewEmployee-shaped, not a role check.
    @GetMapping("/review-summary")
    LeaveReviewSummaryResponse reviewSummary(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new LeaveReviewSummaryResponse(leaveService.reviewSummary(user));
    }

    // Phase A0b AUTHORIZATION CHANGE: leave attachments (e.g. a SICK medical certificate) were
    // upload-only before this endpoint existed -- nothing could read one back. Modelled on
    // SpecialMoneyController#downloadAttachment: authorize BEFORE serving, 404 (not 403) for an
    // unknown id so this cannot be used to probe which ids exist. See
    // LeaveService#resolveAttachmentForDownload for the access predicate.
    //
    // V132 storage-durability fix: resolveAttachmentForDownload has ALREADY confirmed the bytes are
    // available (DATABASE, or DISK_LEGACY with a file that still resolves) before returning --
    // throwing 410 GONE itself when they are not, strictly after its 404/403 checks (see that
    // method's javadoc for why the ordering matters). This method only needs to pick the right
    // byte source for the state it got back.
    @GetMapping("/attachments/{attachmentId}")
    ResponseEntity<Resource> downloadAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        LeaveAttachmentRepository.AttachmentLocation location =
            leaveService.resolveAttachmentForDownload(attachmentId, user);
        Resource resource;
        if ("DATABASE".equals(location.storageState())) {
            byte[] content = attachmentBlobs.findContent(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล"));
            resource = new ByteArrayResource(content);
        } else {
            resource = new FileSystemResource(Paths.get(location.storagePath()));
            if (!resource.exists()) {
                throw new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล");
            }
        }
        MediaType mediaType = location.mimeType() == null
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(location.mimeType());
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(location.fileName()).build().toString())
            .body(resource);
    }
}
