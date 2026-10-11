package th.co.glr.hr.dealquotation;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;

/**
 * A quotation's เอกสารแนบ: the files a rep attaches so the approver can see what the goods cost,
 * typically the vendor's own quotation. They are internal. Nothing prints them or mails them.
 *
 * <p>Phase 1 placeholder. No method below holds a rule yet. Each exists so the tests compile and
 * fail on their assertions, and the tests state the rules.
 */
@Service
public class DealQuotationAttachmentService {
    /** The most files one quotation may hold. Files it inherits from earlier documents of its
     * revision chain are not counted, because they are stored on those documents. */
    public static final int MAX_ATTACHMENTS_PER_QUOTATION = 10;

    private final DealQuotationService quotations;
    private final DealQuotationAttachmentRepository attachments;
    private final FileStorageService fileStorage;

    public DealQuotationAttachmentService(DealQuotationService quotations,
                                          DealQuotationAttachmentRepository attachments,
                                          FileStorageService fileStorage) {
        this.quotations = quotations;
        this.attachments = attachments;
        this.fileStorage = fileStorage;
    }

    /** A stored file, authorized and ready to send. The only way bytes leave this feature. */
    public record AttachmentFile(String fileName, String mimeType, byte[] content) {}

    public List<DealQuotationAttachmentDto> list(long quotationId, UserPrincipal actor) {
        return List.of();
    }

    public DealQuotationAttachmentDto upload(long quotationId, MultipartFile file, UserPrincipal actor) {
        return null;
    }

    public AttachmentFile download(long attachmentId, UserPrincipal actor) {
        return null;
    }

    public void delete(long attachmentId, UserPrincipal actor) {
    }
}
