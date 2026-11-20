package th.co.glr.hr.attachment;

import jakarta.servlet.http.HttpSession;
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
import th.co.glr.hr.auth.OversightReader;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.auth.WarehouseStaff;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.AttachType;
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
        boolean grantOnly = requireTicketReadAccess(ticketId, user);
        return Map.of("attachments", attachments.findByTicketId(ticketId, mayReadRoundFiles(user), !grantOnly));
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
        // A delivery round's files carry prices: refused as if absent, before any other gate.
        if (!mayReadRoundFiles(user) && attachments.isRoundFile(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์");
        }
        boolean grantOnly = requireAttachmentReadAccess(dto, user);
        // The read-all grant never reaches the sales side's private DRAFT round files (refused as if absent).
        if (grantOnly && attachments.roundStatus(id).filter("DRAFT"::equals).isPresent()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์");
        }
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

    /** THE gate for files linked to a delivery round (the stored system documents and the extra files). Import is
     * price-free everywhere (it is refused deposit notices and remaining invoices), so it never sees them. Everyone
     * else passes here and then meets the per-file gate in {@link #requireAttachmentReadAccess}, which is where warehouse
     * staff and invoice-issuer grant holders are admitted to a round past DRAFT / past FORWARDED. */
    private static boolean mayReadRoundFiles(UserPrincipal actor) {
        return !"import".equals(actor.role());
    }

    @Transactional
    @DeleteMapping("/attachments/{id}")
    Map<String, Boolean> delete(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        AttachmentDto dto = attachments.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์"));
        requireAttachmentWriteAccess(dto, user);
        if (AttachType.INVOICE.equals(dto.attachType())) {
            requireInvoiceFileDeletable(id, dto, user);
        }
        // A round's files are frozen once it is sent, except that the uploader of an extra file may still take it back
        // while the warehouse has not forwarded the round (a system-stored copy never; from FORWARDED on nothing).
        attachments.roundStatus(id).filter(st -> !AttachType.INVOICE.equals(dto.attachType())).ifPresent(status -> {
            boolean editable = "DRAFT".equals(status) || "RETURNED".equals(status);
            boolean extra = !"SYSTEM_DOC".equals(dto.attachType());
            boolean own = user.id() == dto.uploadedBy();
            // While editable an extra file is its uploader's alone: the warehouse's photo cannot be removed by sales.
            if (editable && extra && !own) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ลบได้เฉพาะไฟล์ที่ตนเองแนบ");
            }
            boolean ownExtraOnSent = "SENT".equals(status) && own && extra;
            if (!editable && !ownExtraOnSent) {
                throw new ApiException(HttpStatus.CONFLICT, "รอบนี้ส่งไปแล้ว ลบไฟล์ไม่ได้");
            }
        });
        String filePath = attachments.findFilePathById(id);
        // CommissionService#createFromDeal registers this SAME physical file
        // a second time, independently, as ข้อ 15 evidence (hr.file_attachment, referenced by
        // sales.invoice_details.invoice_attachment_id) — and the ticket's own creator is always
        // the deal's sales rep, i.e. the person the commission pays. Without this check, that rep
        // could reach this endpoint (requireAttachmentWriteAccess admits ticket participants) and
        // delete both the row AND the file out from under an approved commission — invoice_
        // attachment_id would stay non-null (the CHECK sees no reason to object) while the
        // file it points at silently stops existing. Refuse outright rather than just skipping
        // the disk delete, so the ticket's own attachment list also stays truthful about what
        // evidence still exists.
        if (attachments.backsLiveCommission(filePath)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถลบไฟล์นี้ได้ เนื่องจากเป็นหลักฐานของค่าคอมมิชชั่นที่ยังไม่ถูกยกเลิก");
        }
        attachments.delete(id);
        auditService.record(user, "DELETE_ATTACHMENT", "attachment", id, dto, null);
        // Deferred to commit, not done here: this method is @Transactional, and a disk delete is
        // not. Removing the bytes inline would let a rollback restore the sales.attachment row while
        // the file it points at stayed gone — a document the deal still lists and nobody can ever
        // download again. See FileStorageService#deleteOnCommit, the mirror of the deleteOnRollback
        // guard for the storing direction.
        fileStorage.deleteOnCommit(filePath);
        return Map.of("ok", true);
    }

    /** The issued tax invoice of a round: only the issuer who attached it, and only while the round is FORWARDED (nothing
     * confirmed, so replacing or removing it moves no money). Once the round is INVOICED the file backs the receipt it
     * recorded and is frozen, as it is once a delivery rests on it. */
    private void requireInvoiceFileDeletable(long id, AttachmentDto dto, UserPrincipal user) {
        var status = attachments.roundStatus(id);
        if (status.isEmpty()) {
            return; // an INVOICE file of the deal itself (commission evidence): the normal gates decide
        }
        if (user.id() != dto.uploadedBy() || !tickets.holdsTaxInvoiceGrant(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ลบได้เฉพาะไฟล์ที่ตนเองแนบ");
        }
        if (!"FORWARDED".equals(status.get())) {
            throw new ApiException(HttpStatus.CONFLICT, "ยืนยันใบกำกับแล้ว ลบไฟล์ไม่ได้");
        }
    }

    /**
     * Reading a deal's documents is the same access question as reading the deal
     * ({@code TicketService#requireViewAccess}) — see {@link TicketAccessPolicy#canViewDocuments}.
     *
     * <p>Read and write are two separate questions, both answered by {@link TicketAccessPolicy}
     * rather than by a role list local to this controller. One blanket manager-role set for both
     * is wrong in both directions — {@code hr} would download every customer contract, PO and tax
     * invoice on every deal despite being refused the deal itself everywhere else, while
     * {@code account} could not open the receipt it is required to confirm money against.
     */
    private boolean requireTicketReadAccess(long ticketId, UserPrincipal actor) {
        TicketSummaryDto summary = requireTicketSummary(ticketId);
        // import and account are row-scoped to their OWN list
        // scope. The scope predicate is a DB query, so it is only run for the role that needs it, and
        // never matters for a participant (short-circuited inside the policy anyway). All four
        // AttachType values stay readable in scope -- there are no others, so no type filter.
        boolean inRoleScope = switch (String.valueOf(actor.role())) {
            // Reading only: the deal's documents open wherever the deal page does, which includes a lost/cancelled
            // deal that still has an issued IR / deposit notice.
            case "import" -> tickets.isInImportScope(ticketId) || tickets.isDeadDealWithIssuedImportRequest(ticketId);
            case "account" -> tickets.isInAccountScope(ticketId) || tickets.isDeadDealWithIssuedDepositNotice(ticketId);
            default -> false;
        };
        if (TicketAccessPolicy.canViewDocuments(summary, actor, inRoleScope,
                tickets.holdsAllSalesDocumentsGrant(actor))) {
            return false;
        }
        // The read-all grant (READ only; import excluded by the helper): admitted, but flagged so DRAFT round files stay hidden.
        if (OversightReader.is(actor, tickets::holdsReadAllSalesGrant)) {
            return true;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /** Attaching or removing a document is strictly narrower than reading one. See
     * TicketAccessPolicy#DOCUMENT_WRITER_ROLES. */
    private void requireTicketWriteAccess(long ticketId, UserPrincipal actor) {
        if (!TicketAccessPolicy.canManageDocuments(requireTicketSummary(ticketId), actor,
                tickets.holdsAllSalesDocumentsGrant(actor))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        return ticket.summary();
    }

    /** The uploader may always reach their own file; everyone else goes through the deal gate. */
    private boolean requireAttachmentReadAccess(AttachmentDto dto, UserPrincipal actor) {
        if (actor.id() == dto.uploadedBy()) {
            return false;
        }
        // The warehouse is refused every deal; it reads the files of a round sales has sent, and nothing else.
        if (WarehouseStaff.is(actor) && attachments.roundStatus(dto.id()).filter(st -> !"DRAFT".equals(st)).isPresent()) {
            return false;
        }
        // The invoice issuer (a per-person grant, any role) reads the files of a round forwarded to it, and nothing else.
        if (tickets.holdsTaxInvoiceGrant(actor.id())
            && attachments.roundStatus(dto.id()).filter(st -> "FORWARDED".equals(st) || "INVOICED".equals(st)).isPresent()) {
            return false;
        }
        return requireTicketReadAccess(dto.ticketId(), actor);
    }

    private void requireAttachmentWriteAccess(AttachmentDto dto, UserPrincipal actor) {
        if (actor.id() == dto.uploadedBy()) {
            return;
        }
        requireTicketWriteAccess(dto.ticketId(), actor);
    }
}
