package th.co.glr.hr.specialmoney;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyAttachmentResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyAttachmentsResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyDetailResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyEmployeeOptionsResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyListResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyTypesResponse;
import th.co.glr.hr.specialmoney.SpecialMoneyResponses.SpecialMoneyUsageResponse;

/** Modelled on {@code th.co.glr.hr.overtime.OvertimeController}: session-resolved principal, no
 * {@code @PreAuthorize} -- every authorization decision lives in {@link SpecialMoneyService}. */
@RestController
@RequestMapping("/api/special-money")
public class SpecialMoneyController {
    private final SpecialMoneyService specialMoneyService;
    private final SessionContext sessions;
    private final FileStorageService fileStorage;

    public SpecialMoneyController(
            SpecialMoneyService specialMoneyService, SessionContext sessions, FileStorageService fileStorage) {
        this.specialMoneyService = specialMoneyService;
        this.sessions = sessions;
        this.fileStorage = fileStorage;
    }

    @GetMapping
    SpecialMoneyListResponse list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "employeeId", required = false) Long employeeId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String requestType,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyListResponse(
            specialMoneyService.list(user, fromDate, toDate, employeeId, status, requestType));
    }

    @PostMapping
    SpecialMoneyDetailResponse submit(@Valid @RequestBody SubmitSpecialMoneyHttpRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(
            specialMoneyService.submit(request.requestType(), request.toDomain(), user));
    }

    @GetMapping("/employees")
    SpecialMoneyEmployeeOptionsResponse employeeOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyEmployeeOptionsResponse(specialMoneyService.employeeOptions(user));
    }

    @GetMapping("/usage")
    SpecialMoneyUsageResponse usage(
            @RequestParam(value = "employeeId") long employeeId,
            @RequestParam(value = "year", required = false) Integer year,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        int effectiveYear = year != null ? year : Year.now().getValue();
        return new SpecialMoneyUsageResponse(specialMoneyService.usage(employeeId, effectiveYear, user));
    }

    @PostMapping("/{id}/approve")
    SpecialMoneyDetailResponse approve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.approve(id, request, user));
    }

    @PostMapping("/{id}/reject")
    SpecialMoneyDetailResponse reject(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.reject(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    SpecialMoneyDetailResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ReviewSpecialMoneyRequest request,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyDetailResponse(specialMoneyService.cancel(id, request, user));
    }

    /** Static reference data, but still behind a session like every other endpoint here. */
    @GetMapping("/types")
    SpecialMoneyTypesResponse types(HttpSession session) {
        sessions.requireUser(session);
        List<SpecialMoneyTypeOption> options = Arrays.stream(SpecialMoneyType.values())
            .map(type -> new SpecialMoneyTypeOption(
                type.name(), type.thaiLabel(), type.payrollBucket().name(), type.evidenceRequired()))
            .toList();
        return new SpecialMoneyTypesResponse(options);
    }

    // ---------------------------------------------------------------------
    // Evidence attachments
    // ---------------------------------------------------------------------

    /** Receipts and life-event documents are scans or photos; nothing else is accepted. */
    private static final Set<String> ALLOWED_EVIDENCE_TYPES =
        Set.of("application/pdf", "image/jpeg", "image/png");

    @PostMapping("/{id}/attachments")
    SpecialMoneyAttachmentResponse addAttachment(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        // Authorize BEFORE writing anything to disk. Storing first and letting the service reject
        // afterwards would leave an orphaned file on every refused upload -- a way for anyone with
        // a session to fill the uploads volume by POSTing to a request that is not theirs.
        specialMoneyService.requireCanAttach(id, user);
        FileStorageService.StoredFile stored =
            fileStorage.store("special-money", id, file, ALLOWED_EVIDENCE_TYPES);
        return new SpecialMoneyAttachmentResponse(specialMoneyService.addAttachment(
            id, stored.fileName(), stored.filePath(), stored.mimeType(), stored.fileSize(), user));
    }

    @GetMapping("/{id}/attachments")
    SpecialMoneyAttachmentsResponse listAttachments(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new SpecialMoneyAttachmentsResponse(specialMoneyService.listAttachments(id, user));
    }

    @GetMapping("/attachments/{attachmentId}")
    ResponseEntity<Resource> downloadAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        SpecialMoneyRepository.AttachmentLocation location =
            specialMoneyService.resolveAttachmentForDownload(attachmentId, user);
        Resource resource = new FileSystemResource(Paths.get(location.storagePath()));
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์เอกสารนี้");
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
