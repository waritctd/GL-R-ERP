package th.co.glr.hr.dealquotation;

import java.net.URLConnection;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.attachment.FileStorageService.StoredContent;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * A quotation's เอกสารแนบ, the files a rep attaches so the approver can see what the goods cost.
 * Typically that is the vendor's own quotation. The files are internal. Nothing prints them, mails
 * them, or names them on the deal's timeline, which account and import read.
 *
 * <p>Every authorization decision about these files is in this class, and each is made about the
 * quotation the file is STORED on:
 * <ul>
 *   <li>List and download: whoever may view that quotation ({@link DealQuotationService#get}),
 *       except the roles {@code account} and {@code import}. Those two open the quotation itself,
 *       and the {@code can_create_quotation} grant does not change what they get here.</li>
 *   <li>Upload: whoever may edit it ({@link DealQuotationService#getForEdit}, the identity rule
 *       of {@code update}), while it is DRAFT or PENDING_APPROVAL.</li>
 *   <li>Delete: the same identity, while it is DRAFT and has no revision open. A rejected draft
 *       stays DRAFT while its resubmission is with the approver, and that resubmission lists
 *       this document's files.</li>
 * </ul>
 * Who may view and who may edit are {@link DealQuotationService}'s rules, called and not copied.
 * A file route takes an attachment id alone, so the id is first looked up within this feature's
 * own rows ({@link DealQuotationAttachmentRepository#findById}). A leave certificate's or a
 * factory quote's id is not found, whoever asks.
 */
@Service
public class DealQuotationAttachmentService {
    /** The most files one quotation may hold. Files it inherits from earlier documents of its
     * revision chain are not counted, because they are stored on those documents. */
    public static final int MAX_ATTACHMENTS_PER_QUOTATION = 10;

    /** The prefix of the storage key, beside {@code leave} and {@code factory-quotes}. */
    private static final String STORAGE_DOMAIN = "deal-quotation";
    /** What leave and tax-allowance evidence accept, and what {@link FileStorageService}'s own
     * refusal names. */
    private static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final String UNSUPPORTED_TYPE = "รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG";
    private static final Set<String> ROLES_WITHOUT_FILES = Set.of("account", "import");
    private static final Set<String> UPLOAD_STATUSES = Set.of(QuotationStatus.DRAFT, QuotationStatus.PENDING_APPROVAL);

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

    /** The quotation's own files, then those of the documents it revises, flagged inherited. */
    public List<DealQuotationAttachmentDto> list(long quotationId, UserPrincipal actor) {
        requireReader(quotationId, actor);
        return attachments.findForQuotation(quotationId);
    }

    @Transactional
    public DealQuotationAttachmentDto upload(long quotationId, MultipartFile file, UserPrincipal actor) {
        DealQuotationDto quotation = requireEditor(quotationId, actor);
        if (!UPLOAD_STATUSES.contains(quotation.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "แนบเอกสารได้เฉพาะใบเสนอราคาที่เป็นร่างหรือรออนุมัติเท่านั้น");
        }
        // ponytail: the count and the insert are two statements, so two uploads racing at nine
        // files can leave eleven. Lock the quotation row here if the limit ever has to be exact.
        if (attachments.countOwn(quotationId) >= MAX_ATTACHMENTS_PER_QUOTATION) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบเสนอราคานี้แนบเอกสารได้สูงสุด " + MAX_ATTACHMENTS_PER_QUOTATION + " ไฟล์");
        }
        requireSomeType(file);
        // Every refusal is above this line or inside storeInDatabase, which writes nothing.
        StoredContent stored = fileStorage.storeInDatabase(STORAGE_DOMAIN, quotationId, file, ALLOWED_TYPES);
        long attachmentId = attachments.save(quotationId, stored, actor.id());
        return attachments.findById(attachmentId).orElseThrow();
    }

    public AttachmentFile download(long attachmentId, UserPrincipal actor) {
        DealQuotationAttachmentDto attachment = requireAttachment(attachmentId);
        requireReader(attachment.quotationId(), actor);
        byte[] content = attachments.findContent(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อผู้ดูแลระบบ"));
        return new AttachmentFile(attachment.fileName(), attachment.mimeType(), content);
    }

    @Transactional
    public void delete(long attachmentId, UserPrincipal actor) {
        DealQuotationAttachmentDto attachment = requireAttachment(attachmentId);
        requireEditor(attachment.quotationId(), actor);
        if (quotations.hasOpenRevision(attachment.quotationId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ลบเอกสารแนบไม่ได้ เนื่องจากมีฉบับแก้ไขของใบเสนอราคานี้ที่ใช้เอกสารนี้อยู่");
        }
        if (attachments.deleteFromDraft(attachmentId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงลบเอกสารแนบไม่ได้");
        }
    }

    private DealQuotationAttachmentDto requireAttachment(long attachmentId) {
        return attachments.findById(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเอกสารแนบนี้"));
    }

    private void requireReader(long quotationId, UserPrincipal actor) {
        quotations.get(quotationId, actor);
        requireRoleWithFiles(actor);
    }

    private DealQuotationDto requireEditor(long quotationId, UserPrincipal actor) {
        DealQuotationDto quotation = quotations.getForEdit(quotationId, actor);
        requireRoleWithFiles(actor);
        return quotation;
    }

    private static void requireRoleWithFiles(UserPrincipal actor) {
        if (ROLES_WITHOUT_FILES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /**
     * Refuses a file that has no type at all: none declared, and a name that gives none away.
     * {@link FileStorageService} looks such a file up in the allow-list with a null, and that
     * lookup throws, which the caller would see as a server error. An empty file is left to
     * {@code storeInDatabase}, which refuses it as empty.
     */
    private static void requireSomeType(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String declared = file.getContentType();
        String name = file.getOriginalFilename();
        boolean typed = (declared != null && !declared.isBlank())
            || (name != null && URLConnection.guessContentTypeFromName(name) != null);
        if (!typed) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UNSUPPORTED_TYPE);
        }
    }
}
