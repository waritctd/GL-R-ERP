package th.co.glr.hr.payroll.declaration;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDownload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceTombstoneRequest;

/**
 * Flat evidence-file routes, sibling of {@link TaxAllowanceDeclarationController}'s
 * {@code /api/payroll/tax-allowances} prefix — mirrors {@code PricingRequestController}'s own
 * split between nested {@code .../{id}/attachments} (upload/list, on that controller) and a flat
 * {@code /pricing-request-attachments/{id}/file} + {@code DELETE} resource (here).
 *
 * <p><b>The download gate is the security-critical part of this whole feature.</b> Both endpoints
 * below delegate entirely to {@code TaxAllowanceDeclarationService#getAttachmentForDownload}/
 * {@code #deleteAttachment}, which re-resolve access against the PARENT declaration on every call
 * — {@code actor.employeeId() == declaration.employeeId() || actor.role().equals("hr")}, never an
 * uploader short-circuit (see {@code AttachmentController#requireAttachmentAccess} for the
 * anti-pattern deliberately not copied here) and never CEO (evidence is a personal document; CEO's
 * read of the declaration's amounts via the register is unaffected).
 */
@RestController
@RequestMapping("/api/payroll")
public class TaxAllowanceAttachmentController {
    private final TaxAllowanceDeclarationService service;
    private final SessionContext sessions;
    private final FileAttachmentBlobRepository attachmentBlobs;

    public TaxAllowanceAttachmentController(TaxAllowanceDeclarationService service, SessionContext sessions,
                                            FileAttachmentBlobRepository attachmentBlobs) {
        this.service = service;
        this.sessions = sessions;
        this.attachmentBlobs = attachmentBlobs;
    }

    // V134 storage-durability fix: service#getAttachmentForDownload has ALREADY confirmed the
    // bytes are available (DATABASE, or DISK_LEGACY with a file that still resolves), throwing 410
    // itself otherwise -- see LeaveController#downloadAttachment's identical shape for the pattern
    // this mirrors.
    @GetMapping("/tax-allowance-attachments/{attachmentId}/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        TaxAllowanceAttachmentDownload download = service.getAttachmentForDownload(attachmentId, user);
        Resource resource;
        if ("DATABASE".equals(download.storageState())) {
            byte[] content = attachmentBlobs.findContent(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล"));
            resource = new ByteArrayResource(content);
        } else {
            resource = new FileSystemResource(download.filePath());
            if (!resource.exists()) {
                throw new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล");
            }
        }
        String mime = download.attachment().mimeType() != null
            ? download.attachment().mimeType()
            : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.attachment().fileName()).build().toString())
            .body(resource);
    }

    @DeleteMapping("/tax-allowance-attachments/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Boolean> delete(
        @PathVariable long attachmentId,
        @RequestBody(required = false) TaxAllowanceTombstoneRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        service.deleteAttachment(attachmentId, request == null ? null : request.reason(), user);
        return Map.of("ok", true);
    }
}
