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
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@RestController
@RequestMapping("/api")
public class AttachmentController {
    private static final Set<String> MANAGER_ROLES = Set.of("hr", "sales_manager", "ceo");

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
        requireTicketAccess(ticketId, user);
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
        requireTicketAccess(ticketId, user);
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
        requireAttachmentAccess(dto, user);
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
        requireAttachmentAccess(dto, user);
        String filePath = attachments.findFilePathById(id);
        attachments.delete(id);
        auditService.record(user, "DELETE_ATTACHMENT", "attachment", id, dto, null);
        if (filePath != null) {
            try { Files.deleteIfExists(Paths.get(filePath)); } catch (IOException ignored) {}
        }
        return Map.of("ok", true);
    }

    private void requireTicketAccess(long ticketId, UserPrincipal actor) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TicketSummaryDto summary = ticket.summary();
        boolean isParticipant = actor.id() == summary.createdById()
            || (summary.assignedToId() != null && actor.id() == summary.assignedToId());
        boolean isManager = MANAGER_ROLES.contains(actor.role());
        if (!isParticipant && !isManager) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireAttachmentAccess(AttachmentDto dto, UserPrincipal actor) {
        if (actor.id() == dto.uploadedBy()) {
            return;
        }
        requireTicketAccess(dto.ticketId(), actor);
    }
}
