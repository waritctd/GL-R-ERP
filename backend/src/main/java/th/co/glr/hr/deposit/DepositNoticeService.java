package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.PaymentTrack;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class DepositNoticeService {
    // No PREPARER constant here. One existed, holding a hardcoded staff name, and nothing read it:
    // the preparer's name reaches a deposit notice from the DB instead, as the NOT NULL DEFAULT on
    // sales.deposit_notice.preparer_name (V12). Deleting the Java copy leaves that default — the
    // only live source — untouched; re-adding it would just create a second place to change a
    // person's name and a silent way for the two to disagree.
    private static final java.util.Set<String> SALES_ROLES  = java.util.Set.of("sales");
    private static final java.util.Set<String> CEO_ROLES    = java.util.Set.of("ceo");
    private static final java.util.Set<String> IMPORT_ROLES = java.util.Set.of("import");
    // Same read rule as TicketService.VIEWER_ROLES: deposit notices are customer
    // financial documents — hr/employee have no business downloading them.
    // sales_manager is read-only oversight here too — never add it to SALES_ROLES/
    // CEO_ROLES/IMPORT_ROLES.
    private static final java.util.Set<String> VIEWER_ROLES =
        java.util.Set.of("sales", "import", "ceo", "account", "sales_manager");

    private final DepositNoticeRepository docs;
    private final TicketRepository   tickets;
    private final NotificationRepository notifications;
    private final DepositNoticeRenderer renderer;
    private final RemainingInvoiceRenderer remainingRenderer;
    private final CustomerRepository customers;
    private final CustomerQuotationRepository quotations;

    public DepositNoticeService(DepositNoticeRepository docs, TicketRepository tickets,
                           NotificationRepository notifications, DepositNoticeRenderer renderer,
                           RemainingInvoiceRenderer remainingRenderer, CustomerRepository customers,
                           CustomerQuotationRepository quotations) {
        this.docs              = docs;
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.renderer          = renderer;
        this.remainingRenderer = remainingRenderer;
        this.customers         = customers;
        this.quotations        = quotations;
    }

    public List<DocumentNoteTemplateDto> getNoteTemplates() {
        return docs.findNoteTemplates();
    }

    public List<DepositNoticeDto> listByTicket(long ticketId, UserPrincipal actor) {
        requireTicketViewer(ticketId, actor);
        return docs.findByTicket(ticketId);
    }

    public DepositNoticeDto getById(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบแจ้งรับมัดจำนี้"));
        requireTicketViewer(doc.ticketId(), actor);
        return doc;
    }

    // Create a DRAFT from approved ticket items
    @Transactional
    public DepositNoticeDto createDraft(long ticketId, DepositNoticeDraftRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireApprovedTicket(ticketId, actor);
        requireActiveLifecycle(s);

        // Auto-populate items from approved ticket items (legacy) or, failing that, the
        // ticket's own customer-quotation chain (new pricing-request flow) if not provided.
        List<DepositNoticeItemRequest> items = buildItemsFromRequest(req, ticketId);
        List<String> notes = req.notes() != null ? req.notes()
            : docs.findNoteTemplates().stream()
                .filter(DocumentNoteTemplateDto::defaultSelected)
                .map(DocumentNoteTemplateDto::text)
                .toList();

        // Header autofill (this branch's fix): customerTaxId/customerAddress/projectName were
        // never populated for a deal created through the pricing-request chain — only
        // customerName had a ticket-summary fallback. Sourced from the customer master via the
        // ticket's own customerId (address = "address branch", matching
        // DepositNoticePage.selectCustomer's own [address, branch].filter(Boolean).join(' ')
        // convention on the frontend) and, for projectName, the ticket summary itself. A caller-
        // supplied non-blank value always wins; a null customerId (never linked to a customer
        // master row) safely leaves these fields blank rather than throwing.
        String customerTaxId = blankToNull(req.customerTaxId());
        String customerAddress = blankToNull(req.customerAddress());
        String projectName = blankToNull(req.projectName());
        if ((customerTaxId == null || customerAddress == null) && s.customerId() != null) {
            CustomerDto customer = customers.findById(s.customerId()).orElse(null);
            if (customer != null) {
                if (customerTaxId == null) {
                    customerTaxId = blankToNull(customer.taxId());
                }
                if (customerAddress == null) {
                    customerAddress = List.of(customer.address(), customer.branch()).stream()
                        .filter(v -> v != null && !v.isBlank())
                        .reduce((a, b) -> a + " " + b)
                        .orElse(null);
                }
            }
        }
        if (projectName == null) {
            projectName = blankToNull(s.projectName());
        }

        var effective = new DepositNoticeDraftRequest(
            req.customerName() != null ? req.customerName() : s.customerName(),
            customerTaxId, customerAddress,
            projectName, req.reference(),
            req.depositPercent() != null ? req.depositPercent() : new BigDecimal("0.50"),
            notes, items
        );

        long docId = docs.createDraft(ticketId, effective, items);
        return docs.findById(docId).orElseThrow();
    }

    @Transactional
    public DepositNoticeDto update(long docId, DepositNoticeDraftRequest req, UserPrincipal actor) {
        DepositNoticeDto doc = requireDraft(docId);
        requireTicketOwner(doc.ticketId(), actor);
        docs.update(docId, req);
        return docs.findById(docId).orElseThrow();
    }

    // Returns HTML preview (PDF requires LibreOffice — mock for now)
    public String preview(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toPreviewHtml(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "แสดงตัวอย่างเอกสารไม่สำเร็จ: " + e.getMessage());
        }
    }

    // Issue: assign doc number, freeze document, transition ticket to document_issued
    @Transactional
    public DepositNoticeDto issue(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = requireDraft(docId);
        requireRole(actor, SALES_ROLES);
        requireTicketOwner(doc.ticketId(), actor);

        // Issuing the deposit-notice DOCUMENT is the payment-track step: it requires a
        // customer-confirmed quotation and advances paymentStatus, leaving the main
        // status at quotation_issued. It no longer flips the ticket to document_issued —
        // that side effect killed the dual-track UI and let unpaid tickets close
        // (2026-07-16 audit findings #3/#4).
        TicketSummaryDto s = tickets.findById(doc.ticketId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
        // Loosened precondition (payment-track state machine, site 2): a deposit notice may be
        // (re-)issued from CUSTOMER_CONFIRMED (first issue) OR from DEPOSIT_NOTICE_ISSUED itself
        // (a revision — PaymentTrack's one legal self-loop; PR #698's own "AND status='DRAFT'"
        // guard on docs.issue is what makes a revision safe to mint a fresh doc number here).
        //
        // The explicit !bypassesDepositNotice(...) guard matters beyond the paymentStatus check
        // above: DEPOSIT_NOTICE_ISSUED is not on a bypass policy's PaymentTrack path AT ALL, so a
        // WAIVED/NOT_REQUIRED/CREDIT_CUSTOMER deal that somehow reached CUSTOMER_CONFIRMED (the
        // paymentStatus check alone would pass) must still be refused HERE, with a clean 409 —
        // not left to fall through to advancePaymentStatus below, which would throw an uncaught
        // IllegalStateException (ApiExceptionHandler has no handler for it, so it would surface as
        // an opaque 500, not a controlled conflict). Found while writing PaymentTrackIntegrationTest's
        // off-path case; a bypass-policy deal CAN reach a DRAFT deposit notice today (createDraft
        // does not check deposit_policy), so this is a genuinely reachable path, not hypothetical.
        // On a bypass policy, issuing a deposit notice now throws — a deliberate behaviour change
        // from rule 4; see the branch report.
        boolean paymentTrackReady = !DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && ("CUSTOMER_CONFIRMED".equals(s.paymentStatus()) || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus()));
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status()) || !paymentTrackReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ออกใบแจ้งรับมัดจำได้เฉพาะเมื่อออกใบเสนอราคาแล้วและลูกค้ายืนยันคำสั่งซื้อแล้วเท่านั้น");
        }
        // Issuing advances the payment track — a paused/terminal deal must not move
        // (Phase 1 lifecycle gate; mirrors TicketService.requireActive).
        requireActiveLifecycle(s);

        String docNumber = docs.issue(docId, actor.id(), actor.name())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "ใบแจ้งรับมัดจำนี้ถูกออกไปแล้วโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง"));

        // Render downloadable files at issue time. For now the DB stores render flags; bytes
        // remain regenerable from the persisted document snapshot.
        try {
            DepositNoticeDto issued = docs.findById(docId).orElseThrow();
            renderer.toPdf(issued);
            renderer.toXlsx(issued);
            docs.setFilePaths(docId, "rendered", "rendered");
        } catch (Exception e) {
            // Non-fatal: files can be regenerated on download.
        }

        // expected = s.paymentStatus(), which by the guard above is exactly CUSTOMER_CONFIRMED
        // (first issue, single hop) or DEPOSIT_NOTICE_ISSUED (revision, the one legal self-loop).
        int rows = tickets.advancePaymentStatus(doc.ticketId(), s.depositPolicy(), s.paymentStatus(),
            PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ขั้นตอนการรับชำระเงินถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        tickets.addEvent(doc.ticketId(), actor.id(), actor.name(),
            TicketEventKind.DEPOSIT_NOTICE_ISSUED,
            s.status(), s.status(),
            "เอกสาร " + docNumber + " ออกแล้ว");

        return docs.findById(docId).orElseThrow();
    }

    // Download Excel bytes
    public byte[] getXlsx(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    public byte[] getPdf(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toPdf(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ PDF ไม่สำเร็จ: " + e.getMessage());
        }
    }

    // ── Remaining Invoice (ข้อ 13.5) ─────────────────────────────────────────

    public byte[] getRemainingInvoiceXlsx(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicketViewer(ticketId, actor);
        TicketSummaryDto s = ticket.summary();
        if (!"quotation_issued".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกใบกำกับภาษีส่วนที่เหลือได้เฉพาะดีลที่อยู่ในสถานะ quotation_issued เท่านั้น");
        }

        // Find issued deposit notice to get deposit amount and reference
        BigDecimal depositAmount = BigDecimal.ZERO;
        String depositRef = null;
        List<DepositNoticeDto> notices = docs.findByTicket(ticketId);
        DepositNoticeDto issued = notices.stream()
            .filter(n -> "ISSUED".equals(n.status()))
            .findFirst()
            .orElse(null);
        if (issued != null) {
            depositAmount = issued.depositAmount() != null ? issued.depositAmount() : BigDecimal.ZERO;
            depositRef = issued.docNumber();
        }

        // Build items from approved ticket items
        int[] seq = {1};
        List<RemainingInvoiceItemDto> items = ticket.items().stream()
            .filter(it -> it.approvedPrice() != null)
            .map(it -> {
                String desc = java.util.stream.Stream.of(
                    it.brand(), it.model(), it.color(), it.texture(), it.size()
                ).filter(v -> v != null && !v.isBlank())
                 .reduce((a, b) -> a + " " + b).orElse(nullSafe(it.brand()));
                String unit = "SQM".equals(it.unitBasis()) ? "ตร.ม." : "แผ่น";
                BigDecimal qty = "SQM".equals(it.unitBasis()) && it.qtySqm() != null
                    ? it.qtySqm() : (it.qty() != null ? it.qty() : BigDecimal.ZERO);
                return new RemainingInvoiceItemDto(seq[0]++, desc, qty, unit, it.approvedPrice());
            })
            .toList();

        // Build a running doc number (GLR + thai year 2 digits + running — sequential per ticket for now)
        int thaiYear = java.time.Year.now().getValue() + 543;
        String docNumber = "GLR" + (thaiYear % 100) + String.format("%03d", ticketId);

        // Get quotation reference
        String quotationRef = depositRef;
        if (quotationRef == null && !notices.isEmpty()) {
            quotationRef = notices.get(0).reference();
        }

        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            docNumber,
            java.time.LocalDate.now(),
            quotationRef,
            nullSafe(s.customerName()),
            "",   // address not in TicketSummaryDto — left blank
            "",
            nullSafe(s.projectName()),
            depositAmount,
            items
        );

        try {
            return remainingRenderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    // Revision flow (Part A of plan)
    @Transactional
    public TicketDto requestRevision(long ticketId, RevisionRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        TicketSummaryDto s = ticket.summary();
        String st = s.status();
        requireActiveLifecycle(s);

        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "ขอแก้ไข revision ได้เฉพาะจากสถานะ approved หรือ document_issued เท่านั้น");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะเจ้าของดีลเท่านั้นที่สามารถขอแก้ไขใบเสนอราคาได้");
        }

        String toStatus = switch (req.scope()) {
            case QTY_OR_NOTE   -> TicketStatus.APPROVED;          // stays approved, new document version
            case PRICE_CHANGE  -> TicketStatus.PRICE_PROPOSED;    // CEO re-approves
            case NEW_ITEM      -> TicketStatus.IN_REVIEW;         // Import re-prices
        };

        // The status move is now an EXPLICIT transition rather than a side effect of logging the
        // event below. It used to ride on TicketRepository.addEvent, which wrote whatever toStatus
        // it was handed as long as TicketStatus.isValid(toStatus) — a membership check, not a
        // transition guard. addEvent no longer touches the column at all, so without this call the
        // revision would log its event and leave the deal sitting where it was.
        //
        // Two of these three edges deliberately move the deal BACKWARDS (approved/document_issued
        // -> price_proposed for the CEO to re-approve, -> in_review for Import to re-price) and one
        // is the approved -> approved self-edge; all six from/to pairs reachable from the guard
        // above are declared in TicketStatus.ALLOWED as intentional. Converging this flow onto the
        // pricing-request revision path is a separate, later piece of work.
        int rows = tickets.transitionStatus(ticketId, st, toStatus);
        if (rows == 0) {
            // Lost compare-and-set: another writer moved sales.ticket.status between the read above
            // and this write. A conflict, never a re-SELECT for a nicer message — see
            // TicketRepository.transitionStatus's own Javadoc.
            throw new ApiException(HttpStatus.CONFLICT,
                "สถานะดีลถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }

        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REVISION_REQUESTED, st, toStatus,
            "[" + req.scope().name() + "] " + req.reason());

        if (req.scope() == RevisionScope.PRICE_CHANGE) {
            notifications.notifyByRole("ceo", ticketId, "REVISION_REQUESTED",
                "Ticket " + s.code() + " ขอแก้ไขราคา — รออนุมัติใหม่");
        } else if (req.scope() == RevisionScope.NEW_ITEM) {
            notifications.notifyByRole("import", ticketId, "REVISION_REQUESTED",
                "Ticket " + s.code() + " มีสินค้าเพิ่มใหม่ — กรุณาตั้งราคา");
        }

        return tickets.findById(ticketId).orElseThrow();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TicketSummaryDto requireApprovedTicket(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        String st = t.summary().status();
        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.QUOTATION_ISSUED.equals(st)
                && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "สร้างใบแจ้งรับมัดจำได้เฉพาะดีลที่อนุมัติแล้วเท่านั้น");
        }
        return t.summary();
    }

    /**
     * Phase 1 lifecycle gate (mirrors TicketService.requireActive): deposit-notice
     * mutations advance the payment track, so a paused/terminal deal blocks them.
     */
    private void requireActiveLifecycle(TicketSummaryDto summary) {
        if (!DealLifecycle.ACTIVE.equals(summary.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลไม่ได้อยู่ในสถานะ ACTIVE (" + summary.lifecycle() + ") จึงแก้ไขขั้นตอนนี้ไม่ได้");
        }
    }

    private DepositNoticeDto requireDraft(long docId) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบแจ้งรับมัดจำนี้"));
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)");
        }
        return doc;
    }

    /**
     * Read gate mirroring TicketService.requireViewAccess: viewer role required,
     * sales reps only for their own tickets.
     *
     * <p>Phase B (role-scoped views): import is denied outright, unlike
     * TicketService.requireViewAccess (which strips only the embedded quotation but
     * still lets import view the rest of the ticket). A deposit notice IS a customer
     * financial document end to end — there is no "rest of it" for import to see —
     * so every read here (list/get/preview/download/remaining-invoice) is off-limits,
     * matching salesViewScope.js hiding the whole "depositNotice" section from import.
     */
    private TicketDto requireTicketViewer(long ticketId, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        if ("sales".equals(actor.role()) && t.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return t;
    }

    private void requireTicketOwner(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        if (t.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void requireRole(UserPrincipal actor, java.util.Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<DepositNoticeItemRequest> buildItemsFromRequest(DepositNoticeDraftRequest req, long ticketId) {
        if (req.items() != null && !req.items().isEmpty()) return req.items();

        // Legacy path: sales.ticket_item.approved_price, written only by the @Deprecated
        // TicketService.approve (no controller route) — kept working for any ticket that still
        // carries it, e.g. one manually approved before the pricing-request chain existed.
        List<DepositNoticeItemRequest> legacyItems = buildLegacyItems(ticketId);
        if (!legacyItems.isEmpty()) return legacyItems;

        // New-chain fallback (this branch's fix): every deal created through the pricing-request
        // chain has approved_price = NULL on every ticket_item (see this class's own diagnosis in
        // the branch handoff) — buildLegacyItems above always returns empty for such a deal, which
        // is exactly the bug. Source items from the ticket's own customer quotation instead.
        CustomerQuotationDto chosen = pickQuotation(quotations.findByTicket(ticketId));
        if (chosen != null) {
            return itemsFromQuotation(chosen.items());
        }
        return List.of();
    }

    private List<DepositNoticeItemRequest> buildLegacyItems(long ticketId) {
        return tickets.findById(ticketId)
            .map(t -> {
                int[] seq = {1};
                return t.items().stream()
                    .filter(it -> it.approvedPrice() != null)
                    .map(it -> {
                        String desc = List.of(
                            it.brand(), it.model(), it.color(), it.texture(), it.size()
                        ).stream().filter(v -> v != null && !v.isBlank())
                            .reduce((a, b) -> a + " " + b).orElse(it.brand());
                        BigDecimal price = it.approvedPrice();
                        return new DepositNoticeItemRequest(
                            seq[0]++, desc, it.qty(), "แผ่น", price, null, price
                        );
                    })
                    .toList();
            })
            .orElse(List.of());
    }

    /**
     * Picks the quotation to source deposit-notice items from when no explicit items were given
     * and no legacy {@code approved_price} exists: the LATEST ACCEPTED revision, or — if the
     * customer has not yet accepted any revision — the latest ISSUED one. {@code
     * CustomerQuotationRepository#findByTicket} returns rows ordered ASCENDING by {@code
     * quotation_id} ALONE — deliberately NOT by {@code quotation_revision_no} (see that method's
     * own Javadoc: the revision counter is scoped to a single pricing request and is not
     * comparable across the multiple pricing requests one ticket can have) — so a single forward
     * scan that keeps overwriting "latest seen" for each status is equivalent to sorting
     * descending by {@code quotation_id} and taking the first match, without a second
     * list/comparator.
     */
    private CustomerQuotationDto pickQuotation(List<CustomerQuotationDto> candidates) {
        CustomerQuotationDto latestAccepted = null;
        CustomerQuotationDto latestIssued = null;
        for (CustomerQuotationDto q : candidates) {
            if (QuotationStatus.ACCEPTED.equals(q.docStatus())) {
                latestAccepted = q;
            } else if (QuotationStatus.ISSUED.equals(q.docStatus())) {
                latestIssued = q;
            }
        }
        return latestAccepted != null ? latestAccepted : latestIssued;
    }

    /**
     * Maps a customer quotation's items to deposit-notice item requests. The single shared mapping
     * used by both {@link #buildItemsFromRequest} (this class's own ticket-chain fallback above)
     * and {@code OrderConfirmationService.createDepositNoticeFromQuotation} (the explicit
     * quotation-driven entry point), so the two paths can never drift apart — extracted here
     * (rather than duplicated, as it was before this branch) because both already depend on this
     * package for {@link DepositNoticeItemRequest} itself.
     */
    public static List<DepositNoticeItemRequest> itemsFromQuotation(List<CustomerQuotationItemDto> quotationItems) {
        List<DepositNoticeItemRequest> items = new ArrayList<>();
        for (CustomerQuotationItemDto item : quotationItems) {
            String description = item.description() != null && !item.description().isBlank()
                ? item.description() : "รายการสินค้า";
            BigDecimal discount = item.salesDiscount();
            String discountLabel = discount != null && discount.signum() > 0
                ? "ส่วนลด " + discount.stripTrailingZeros().toPlainString() + " ต่อหน่วย" : null;
            items.add(new DepositNoticeItemRequest(
                item.seq(), description, item.requestedQuantity(), unitLabel(item.requestedUnitBasis()),
                item.approvedUnitPrice(), discountLabel, item.finalUnitPrice()));
        }
        return items;
    }

    public static String unitLabel(String unitBasis) {
        if (unitBasis == null) return "หน่วย";
        return switch (unitBasis) {
            case UnitBasis.PER_SQM -> "ตร.ม.";
            case UnitBasis.PER_PIECE -> "แผ่น";
            case UnitBasis.PER_BOX -> "กล่อง";
            case UnitBasis.PER_LINEAR_M -> "เมตร";
            default -> unitBasis;
        };
    }
}
