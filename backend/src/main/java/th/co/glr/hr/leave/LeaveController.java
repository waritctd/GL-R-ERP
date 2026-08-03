package th.co.glr.hr.leave;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
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
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.leave.LeaveResponses.LeaveBalancesResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveContactDefaultsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveDetailResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveEmployeeOptionsResponse;
import th.co.glr.hr.leave.LeaveResponses.LeaveListResponse;
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

    public LeaveController(
            LeaveService leaveService,
            SessionContext sessions,
            LeavePolicyDocumentRepository leavePolicyDocuments) {
        this.leaveService = leaveService;
        this.sessions = sessions;
        this.leavePolicyDocuments = leavePolicyDocuments;
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
}
