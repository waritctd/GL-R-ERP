package th.co.glr.hr.attachment;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.TicketAccessPolicy;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentRepository attachments;
    private final SessionContext sessions;
    private final TicketRepository tickets;
    private final AuditService auditService;
    private final FileStorageService fileStorage;

    public AttachmentController(AttachmentRepository attachments, SessionContext sessions,
                                TicketRepository tickets, AuditService auditService,
                                FileStorageService fileStorage) {
        this.attachments  = attachments;
        this.sessions     = sessions;
        this.tickets      = tickets;
        this.auditService = auditService;
        this.fileStorage  = fileStorage;
    }

    @GetMapping("/tickets/{ticketId}/attachments")
    Map<String, List<AttachmentDto>> list(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        requireTicketReadAccess(ticketId, user);
        return Map.of("attachments", attachments.findByTicketId(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/attachments")
    Map<String, AttachmentDto> upload(
        @PathVariable long ticketId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "attachType", defaultValue = "OTHER") String attachType,
        @RequestParam(value = "quotationId", required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireTicketWriteAccess(ticketId, user);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");

        FileStorageService.StoredFile storedFile = fileStorage.store("tickets", ticketId, file, Set.of());
        AttachmentDto dto = attachments.save(ticketId, quotationId, storedFile.fileName(),
            storedFile.filePath(), storedFile.mimeType(), storedFile.fileSize(), attachType.toUpperCase(), user.id());
        return Map.of("attachment", dto);
    }

    @GetMapping("/attachments/{id}/file")
    ResponseEntity<Resource> download(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        AttachmentDto dto = attachments.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์"));
        requireAttachmentReadAccess(dto, user);
        String filePath = attachments.findFilePathById(id);
        if (filePath == null) throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์บนเซิร์ฟเวอร์");

        Resource resource = new FileSystemResource(filePath);
        if (!resource.exists()) throw new ApiException(HttpStatus.NOT_FOUND, "ไฟล์ถูกลบออกจากเซิร์ฟเวอร์แล้ว");

        String mime = dto.mimeType() != null ? dto.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(dto.fileName()).build().toString())
            .body(resource);
    }

    @Transactional
    @DeleteMapping("/attachments/{id}")
    Map<String, Boolean> delete(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        AttachmentDto dto = attachments.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์"));
        requireAttachmentWriteAccess(dto, user);
        String filePath = attachments.findFilePathById(id);
        // F5 fix (2026-07-30): CommissionService#createFromDeal registers this SAME physical file
        // a second time, independently, as ข้อ 15 evidence (hr.file_attachment, referenced by
        // sales.invoice_details.invoice_attachment_id) — and the ticket's own creator is always
        // the deal's sales rep, i.e. the person the commission pays. Without this check, that rep
        // could reach this endpoint (requireAttachmentWriteAccess admits ticket participants) and
        // delete both the row AND the file out from under an approved commission — invoice_
        // attachment_id would stay non-null (V102's CHECK sees no reason to object) while the
        // file it points at silently stops existing. Refuse outright rather than just skipping
        // the disk delete, so the ticket's own attachment list also stays truthful about what
        // evidence still exists.
        if (attachments.backsLiveCommission(filePath)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถลบไฟล์นี้ได้ เนื่องจากเป็นหลักฐานของค่าคอมมิชชั่นที่ยังไม่ถูกยกเลิก");
        }
        attachments.delete(id);
        auditService.record(user, "DELETE_ATTACHMENT", "attachment", id, dto, null);
        if (filePath != null) {
            try { Files.deleteIfExists(Paths.get(filePath)); } catch (IOException ignored) {}
        }
        return Map.of("ok", true);
    }

    /**
     * Reading a deal's documents is the same access question as reading the deal
     * ({@code TicketService#requireViewAccess}) — see {@link TicketAccessPolicy#canViewDocuments}.
     *
     * <p>#389: this used to be one blanket {@code MANAGER_ROLES = {hr, sales_manager, ceo}} set
     * that answered BOTH read and write, and it was wrong in both directions — {@code hr} could
     * download every customer contract, PO and tax invoice on every deal despite being refused the
     * deal itself everywhere else, while {@code account} could not open the receipt it is required
     * to confirm money against. Read and write are now two separate questions, both answered by
     * {@link TicketAccessPolicy} rather than by a role list local to this controller.
     */
    private void requireTicketReadAccess(long ticketId, UserPrincipal actor) {
        if (!TicketAccessPolicy.canViewDocuments(requireTicketSummary(ticketId), actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    /**
     * Attaching or removing a document is strictly narrower than reading one — notably {@code
     * account} may read every deal's documents but may NOT upload here, because the tax invoice
     * has exactly one entry point ({@code CommissionService#createFromDeal}) and a second one
     * would satisfy the close gate's {@code invoiceOnFile} check without ever creating the rep's
     * commission. See {@link TicketAccessPolicy#DOCUMENT_WRITER_ROLES}.
     */
    private void requireTicketWriteAccess(long ticketId, UserPrincipal actor) {
        if (!TicketAccessPolicy.canManageDocuments(requireTicketSummary(ticketId), actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        return ticket.summary();
    }

    /** The uploader may always reach their own file; everyone else goes through the deal gate. */
    private void requireAttachmentReadAccess(AttachmentDto dto, UserPrincipal actor) {
        if (actor.id() == dto.uploadedBy()) {
            return;
        }
        requireTicketReadAccess(dto.ticketId(), actor);
    }

    private void requireAttachmentWriteAccess(AttachmentDto dto, UserPrincipal actor) {
        if (actor.id() == dto.uploadedBy()) {
            return;
        }
        requireTicketWriteAccess(dto.ticketId(), actor);
    }
}
