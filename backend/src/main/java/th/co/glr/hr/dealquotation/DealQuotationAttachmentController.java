package th.co.glr.hr.dealquotation;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
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
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;

/**
 * The routes of a quotation's เอกสารแนบ. The file routes take an attachment id alone, as the
 * pricing-request and factory-quote file routes do.
 *
 * <p>Phase 1 placeholder. The four routes exist and do nothing. They check no session and do not
 * call {@link DealQuotationAttachmentService}.
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

    @PostMapping("/deal-quotations/{id}/attachments")
    Map<String, DealQuotationAttachmentDto> upload(@PathVariable long id, @RequestParam("file") MultipartFile file,
                                                   HttpSession session) {
        return Map.of();
    }

    @GetMapping("/deal-quotations/{id}/attachments")
    Map<String, List<DealQuotationAttachmentDto>> list(@PathVariable long id, HttpSession session) {
        return Map.of();
    }

    @GetMapping("/deal-quotation-attachments/{attachmentId}/file")
    ResponseEntity<byte[]> file(@PathVariable long attachmentId, HttpSession session) {
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/deal-quotation-attachments/{attachmentId}")
    Map<String, Boolean> delete(@PathVariable long attachmentId, HttpSession session) {
        return Map.of();
    }
}
