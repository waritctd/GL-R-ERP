package th.co.glr.hr.dealquotation;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationAttachmentService.AttachmentFile;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;

/**
 * The routes of a quotation's เอกสารแนบ. The file routes take an attachment id alone, as the
 * pricing-request and factory-quote file routes do.
 *
 * <p>Every authorization decision is in {@link DealQuotationAttachmentService}. This class
 * resolves the session and hands over, and the bytes it sends are the ones that service returned.
 */
@RestController
@RequestMapping("/api")
public class DealQuotationAttachmentController {
    private final DealQuotationAttachmentService attachments;
    private final SessionContext sessions;

    public DealQuotationAttachmentController(DealQuotationAttachmentService attachments, SessionContext sessions) {
        this.attachments = attachments;
        this.sessions = sessions;
    }

    /** Takes a multipart {@code file}, which must be a PDF, a JPEG or a PNG. */
    @PostMapping("/deal-quotations/{id}/attachments")
    Map<String, DealQuotationAttachmentDto> upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
                                                   HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("attachment", attachments.upload(id, file, user));
    }

    @GetMapping("/deal-quotations/{id}/attachments")
    Map<String, List<DealQuotationAttachmentDto>> list(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", attachments.list(id, user));
    }

    /**
     * Always a download and never a page. The response carries the {@code attachment}
     * disposition and {@code nosniff}, because the stored type was only ever declared by the
     * uploader and the browser must not render it under this origin. It carries {@code no-store}
     * because the file is a vendor's price.
     */
    @GetMapping("/deal-quotation-attachments/{attachmentId}/file")
    ResponseEntity<byte[]> file(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        AttachmentFile file = attachments.download(attachmentId, user);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.content());
    }

    @DeleteMapping("/deal-quotation-attachments/{attachmentId}")
    Map<String, Boolean> delete(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        attachments.delete(attachmentId, user);
        return Map.of("ok", true);
    }
}
