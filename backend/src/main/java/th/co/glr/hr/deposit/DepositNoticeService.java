package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import th.co.glr.hr.ticket.PaymentReceiptDto;
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
        // master row) safely leaves these fields blank rather than throwing. Shared with
        // getRemainingInvoiceOptions/getRemainingInvoiceXlsx below (resolveCustomerHeader) so the
        // two documents can never source a customer's address/tax id differently.
        CustomerHeaderInfo header = resolveCustomerHeader(s, req.customerTaxId(), req.customerAddress(), req.projectName());

        var effective = new DepositNoticeDraftRequest(
            req.customerName() != null ? req.customerName() : s.customerName(),
            header.taxId(), header.address(),
            header.projectName(), req.reference(),
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

        // Render downloadable files at issue time — but AFTER this transaction commits, never
        // inside it. toPdf shells out to LibreOffice (LibreOfficePdfConverter forks soffice and
        // blocks on proc.waitFor(120, SECONDS)), and at this point the transaction is holding a
        // pooled connection plus a row lock on the single sales.document_sequence row for
        // (DEPOSIT_NOTICE, this Thai year) that nextDocNumber incremented inside docs.issue above.
        // That row is the serialization point for EVERY deposit notice issued in the whole year, so
        // holding it across an external process turns one slow render into a queue nobody can see:
        // the next rep to issue simply blocks until the first rep's soffice exits.
        //
        // Safe to defer because the render participates in no invariant here. Both renders return
        // byte[] that this method DISCARDS, nothing durable is written (LibreOfficePdfConverter
        // deletes its temp files in a finally), and setFilePaths only stores the literal flags
        // "rendered"/"rendered" — surfacing as DepositNoticeDto.hasPdf/hasXlsx, which no production
        // frontend code reads; getPdf/getXlsx re-render from the persisted snapshot and never
        // consult those columns. The doc number and the DRAFT->ISSUED compare-and-set both stay
        // exactly where they were, in this transaction, so neither the sequence race nor the
        // double-issue race DepositNoticeRepository#issue guards is affected.
        //
        // One deliberate, API-observable consequence: the DTO returned below is built before this
        // runs, so an issue() response now reports hasPdf/hasXlsx false and a subsequent read
        // reports them true.
        renderAfterCommit(docId);

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
    //
    // Stateless by owner decision: no table, no migration. Every field on RemainingInvoiceDto is
    // recomputed fresh on each call from the ticket's own data — the dialog's "options" preview
    // and the actual "/file" download MUST agree, so both funnel through the same
    // resolveRemainingInvoice pipeline (and its shared helpers: resolveCustomerHeader,
    // itemsFromQuotationForRemainingInvoice, itemsFromDepositNoticeForRemainingInvoice,
    // quotationLineAmount, resolveDepositReferenceOptions) rather than each re-deriving defaults
    // their own way.
    //
    // Quotation selection (owner ruling, 2026-09-17). A deal can carry several pricing requests
    // (designer asks first, owner/buyer makes the final one), each producing its own
    // CustomerQuotation chain. A quotation QUALIFIES as a remaining-invoice source when it is
    // ACCEPTED and either:
    //   (a) the ticket's deposit policy bypasses the deposit notice entirely
    //       (DepositPolicy.bypassesDepositNotice — WAIVED/NOT_REQUIRED/CREDIT_CUSTOMER), in which
    //       case it qualifies with NO deposit deduction and no deposit-reference row; or
    //   (b) it has a matching ISSUED deposit notice — matched on
    //       deposit_notice.reference == quotation.number, which is exactly what
    //       OrderConfirmationService#createDepositNoticeFromQuotation writes into that column
    //       (see that method's own DepositNoticeDraftRequest construction: `accepted.number()` is
    //       its 5th positional arg, `reference`). "Latest version" of a matched notice = the
    //       first ISSUED hit in `notices`, which DepositNoticeRepository#findByTicket already
    //       returns DESC by version.
    // Exactly one qualifying quotation -> used automatically. Several -> the caller may pick one
    // via `quotationId` (must be one of THIS ticket's qualifying quotations — see
    // findQualifyingQuotations/resolveRemainingInvoice below); a foreign or non-qualifying id is
    // refused with 400, never silently substituted. None qualifying but an ACCEPTED quotation
    // exists under a deposit-required policy -> blocked (409 on /file, blockingReason on
    // /options — see ResolvedRemainingInvoice) rather than silently falling back to an unrelated
    // source. Only when there is NO accepted quotation at all do the pre-existing legacy
    // fallbacks below (issued deposit-notice item snapshot, then ticket_item.approved_price)
    // apply — kept for any deal manually approved before the pricing-request chain existed.
    //
    // Item/deduction SOURCE (owner ruling D11, 2026-09-17 — supersedes the quotation-sourced-items
    // design above; the QUALIFICATION rules (a)/(b) just above are UNCHANGED, only where the
    // ITEMS and the deduction amount come from changes). When a quotation qualifies via (b) — a
    // matched ISSUED deposit notice — the remaining invoice's item rows AND its deposit deduction
    // both come from that matched notice's OWN latest ISSUED item snapshot
    // (itemsFromDepositNoticeForRemainingInvoice), never from the quotation's own items. The
    // quotation only identifies WHICH deposit notice counts (via the reference match above); it no
    // longer supplies the rows. This drops the old "matched notice's subtotal must equal the
    // quotation's own item total" rule entirely (see resolveFromQuotation) — a deposit notice can
    // be edited/re-issued at a different amount than the quotation, and that edited figure IS the
    // agreed amount now, not a mismatch to block on. The negative-net refusal (rule C below) still
    // applies, computed off the notice's own items. When a quotation qualifies via (a) — a bypass
    // policy with no deposit notice to source from — items still come from the quotation itself
    // (itemsFromQuotationForRemainingInvoice), unchanged; that is the ONLY case items are still
    // quotation-sourced. Both this document and resolveLegacy's own back-compat path (reached only
    // when there is no accepted quotation at all) already sourced items from a deposit notice's own
    // snapshot the same way, so this ruling brings the two paths onto one shared item-sourcing
    // convention instead of two.

    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    /** Prefill + preview for the download dialog. Never throws for an over-capacity item list, a
     * negative net amount, or a not-yet-issued deposit notice — those are all reported via
     * {@code blockingReason} (and itemCount/maxItems for capacity), so
     * the dialog can render a clear refusal instead of a failed round trip. DOES throw
     * (404/403/409) for the same gates {@link #getRemainingInvoiceXlsx} enforces: viewer role,
     * ticket status, and "no priceable source at all" — a preview for a deal with nothing to
     * preview is not useful, and hiding that behind zeros would misinform the caller. */
    public RemainingInvoiceOptionsDto getRemainingInvoiceOptions(long ticketId, UserPrincipal actor) {
        return getRemainingInvoiceOptions(ticketId, null, actor);
    }

    /** {@code quotationId}: optional live-preview selector — when several quotations qualify, the
     * caller may ask for a specific one's preview (must be one of THIS ticket's qualifying
     * quotations; {@code null} previews the newest qualifying quotation, i.e. {@code
     * defaultQuotationId}). Never allows previewing another ticket's quotation — an id outside
     * this ticket's own qualifying set is refused with 400, the same as {@link
     * #getRemainingInvoiceXlsx}'s own {@code quotationId} gate. */
    public RemainingInvoiceOptionsDto getRemainingInvoiceOptions(long ticketId, Long quotationId, UserPrincipal actor) {
        TicketDto ticket = requireTicketViewer(ticketId, actor);
        TicketSummaryDto s = ticket.summary();
        requireQuotationIssuedForRemainingInvoice(s);

        List<CustomerQuotationDto> quotationCandidates = quotations.findByTicket(ticketId);
        List<DepositNoticeDto> notices = docs.findByTicket(ticketId);
        ResolvedRemainingInvoice resolved = resolveRemainingInvoice(
            ticketId, ticket, s, quotationId, quotationCandidates, notices);

        List<RemainingInvoiceItemDto> items = resolved.items();
        BigDecimal depositAmount = resolved.depositAmount();
        int depositRowCount = depositAmount.compareTo(BigDecimal.ZERO) != 0 ? 1 : 0;
        BigDecimal itemsTotal = sumAmounts(items);
        BigDecimal netAmount = itemsTotal.subtract(depositAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatAmount = netAmount.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPayable = netAmount.add(vatAmount).setScale(2, RoundingMode.HALF_UP);

        // Notes-capacity pre-check (finding 2c): only the DEFAULT note selection is knowable here
        // (this endpoint takes no noteIds — the dialog lets the caller customise notes only right
        // before download), so this can only warn about the prefill the dialog will start from.
        // A caller who then picks a different, still-too-long set gets the authoritative 409 from
        // getRemainingInvoiceXlsx itself (surfaced to the user — see hrApi.js's error-message fix).
        String blockingReason = resolved.blockingReason();
        if (blockingReason == null) {
            List<String> defaultNotes = resolveNotes(null);
            if (RemainingInvoiceRenderer.wrapNotes(defaultNotes).size() > RemainingInvoiceRenderer.MAX_NOTE_LINES) {
                blockingReason = "หมายเหตุเริ่มต้นยาวเกินพื้นที่ในแบบฟอร์ม (สูงสุด "
                    + RemainingInvoiceRenderer.MAX_NOTE_LINES + " บรรทัด) กรุณาลดจำนวนหรือย่อข้อความหมายเหตุ";
            }
        }

        return new RemainingInvoiceOptionsDto(
            buildRemainingInvoiceDocNumber(ticketId),
            bangkokToday(),
            resolved.defaultReference(),
            resolved.referenceOptions(),
            resolved.defaultDepositReference(),
            resolved.depositReferenceOptions(),
            getNoteTemplates(),
            // Counts the deposit-deduction row too (when it will render) — maxItems is the
            // template's total row capacity, not just the item-line capacity, so the dialog can
            // compare itemCount > maxItems directly without re-deriving whether a deposit row applies.
            items.size() + depositRowCount,
            RemainingInvoiceRenderer.MAX_ITEM_ROWS,
            itemsTotal.setScale(2, RoundingMode.HALF_UP),
            depositAmount,
            netAmount,
            vatAmount,
            totalPayable,
            resolved.quotationOptions(),
            resolved.defaultQuotationId(),
            blockingReason
        );
    }

    /**
     * Extended (branch fix): {@code reference}/{@code depositReference}/{@code issueDate}/
     * {@code noteIds} are ALL optional — a bare call with every one {@code null} reproduces the
     * dialog's own defaults exactly (owner decision: a one-click download with no params must
     * keep working). Semantics live at the controller boundary: {@code null} = "use the default",
     * a non-null-but-blank {@code reference}/{@code depositReference} = "leave that cell blank",
     * and {@code noteIds} present-but-empty = "no notes" — see DepositNoticeController.
     */
    public byte[] getRemainingInvoiceXlsx(long ticketId, UserPrincipal actor,
            String reference, String depositReference, LocalDate issueDate, List<Long> noteIds) {
        return getRemainingInvoiceXlsx(ticketId, actor, reference, depositReference, issueDate, noteIds, null);
    }

    /** Same as the 6-arg overload, plus {@code quotationId} (owner ruling — see this section's own
     * header comment): optional when exactly one quotation qualifies, REQUIRED-in-effect when
     * several do (an unset id silently picks the newest, which the dialog always shows as
     * {@code defaultQuotationId} so this is never a surprise). An id outside this ticket's own
     * qualifying set — including any other ticket's quotation id — is refused with 400. */
    public byte[] getRemainingInvoiceXlsx(long ticketId, UserPrincipal actor,
            String reference, String depositReference, LocalDate issueDate, List<Long> noteIds, Long quotationId) {
        TicketDto ticket = requireTicketViewer(ticketId, actor);
        TicketSummaryDto s = ticket.summary();
        requireQuotationIssuedForRemainingInvoice(s);

        List<CustomerQuotationDto> quotationCandidates = quotations.findByTicket(ticketId);
        List<DepositNoticeDto> notices = docs.findByTicket(ticketId);
        ResolvedRemainingInvoice resolved = resolveRemainingInvoice(
            ticketId, ticket, s, quotationId, quotationCandidates, notices);
        if (resolved.blockingReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT, resolved.blockingReason());
        }

        List<RemainingInvoiceItemDto> items = resolved.items();
        BigDecimal depositAmount = resolved.depositAmount();
        int depositRowCount = depositAmount.compareTo(BigDecimal.ZERO) != 0 ? 1 : 0;
        if (items.size() + depositRowCount > RemainingInvoiceRenderer.MAX_ITEM_ROWS) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบแจ้งหนี้ส่วนที่เหลือมีรายการ " + items.size() + " รายการ"
                + (depositRowCount > 0 ? " บวกแถวหักมัดจำ" : "")
                + " เกินความจุของแบบฟอร์ม (สูงสุด " + RemainingInvoiceRenderer.MAX_ITEM_ROWS + " แถว)"
                + " กรุณารวมรายการหรือออกเอกสารหลายฉบับ");
        }

        CustomerHeaderInfo header = resolveCustomerHeader(s, null, null, null);
        String effectiveReference = reference != null ? reference : resolved.defaultReference();
        String effectiveDepositReference = depositReference != null ? depositReference : resolved.defaultDepositReference();
        LocalDate effectiveIssueDate = issueDate != null ? issueDate : bangkokToday();
        List<String> effectiveNotes = resolveNotes(noteIds);
        // Notes-capacity gate (finding 2c): refuse rather than silently clip. Uses the SAME
        // wrapNotes the renderer itself lays the หมายเหตุ block out with, so this check and the
        // actual render can never disagree about what fits.
        if (RemainingInvoiceRenderer.wrapNotes(effectiveNotes).size() > RemainingInvoiceRenderer.MAX_NOTE_LINES) {
            throw new ApiException(HttpStatus.CONFLICT,
                "หมายเหตุที่เลือกยาวเกินพื้นที่ในแบบฟอร์ม (สูงสุด " + RemainingInvoiceRenderer.MAX_NOTE_LINES
                + " บรรทัด) กรุณาลดจำนวนหรือย่อข้อความหมายเหตุ");
        }

        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            buildRemainingInvoiceDocNumber(ticketId),
            effectiveIssueDate,
            effectiveReference,
            effectiveDepositReference,
            nullSafe(s.customerName()),
            header.branch(),
            // B8 = address ONLY for the remaining invoice (finding 2d) — B7 already shows the
            // branch in parens via customerHeaderLine, so folding it into the address too (the
            // way header.address()/createDraft does for the deposit notice — deliberately
            // UNCHANGED, see resolveCustomerHeader's own Javadoc) printed the branch twice.
            nullSafe(header.addressOnly()),
            nullSafe(header.taxId()),
            nullSafe(header.projectName()),
            depositAmount,
            effectiveNotes,
            items
        );

        try {
            return remainingRenderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    private void requireQuotationIssuedForRemainingInvoice(TicketSummaryDto s) {
        if (!"quotation_issued".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกใบกำกับภาษีส่วนที่เหลือได้เฉพาะดีลที่อยู่ในสถานะ quotation_issued เท่านั้น");
        }
    }

    private String buildRemainingInvoiceDocNumber(long ticketId) {
        // GLR + Thai year (2 digits) + running — unchanged formula (sequential per ticket, not a
        // real document sequence; this document is stateless by owner decision).
        int thaiYear = bangkokToday().getYear() + 543;
        return "GLR" + (thaiYear % 100) + String.format("%03d", ticketId);
    }

    private LocalDate bangkokToday() {
        return LocalDate.now(ZoneId.of("Asia/Bangkok"));
    }

    /**
     * One resolved bundle: the priced items, the deposit amount/reference defaults, both dialog
     * suggestion lists, the quotation-picker options, and (when the deal cannot be safely priced
     * right now) a human {@code blockingReason} — computed together so
     * {@link #getRemainingInvoiceOptions} and {@link #getRemainingInvoiceXlsx} can never disagree
     * on what "the default"/"the block" is. {@code blockingReason != null} means {@code items}/
     * {@code depositAmount} are NOT safe to render (empty/zero) — {@link #getRemainingInvoiceXlsx}
     * turns it straight into a 409; {@link #getRemainingInvoiceOptions} surfaces it as-is so the
     * dialog can disable the download button instead of failing a round trip. The one exception is
     * the negative-net case (rule C): items/depositAmount ARE both individually valid there — it
     * is only their difference that is wrong — so both are still returned alongside the reason, to
     * give the dialog something to explain the block with.
     */
    private record ResolvedRemainingInvoice(
        List<RemainingInvoiceItemDto> items,
        BigDecimal depositAmount,
        String defaultReference,
        List<RemainingInvoiceOptionsDto.ReferenceOption> referenceOptions,
        String defaultDepositReference,
        List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions,
        List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions,
        Long defaultQuotationId,
        String blockingReason
    ) {}

    /** One quotation this ticket's remaining invoice COULD be sourced from, paired with the
     * deposit notice it matched to (see this section's header comment for the matching rule) —
     * {@code null} when the ticket's deposit policy bypasses the deposit notice entirely, meaning
     * this quotation qualifies with no deduction at all. */
    private record QualifyingQuotation(CustomerQuotationDto quotation, DepositNoticeDto matchedNotice) {}

    private List<QualifyingQuotation> findQualifyingQuotations(List<CustomerQuotationDto> candidates,
            List<DepositNoticeDto> notices, String depositPolicy) {
        boolean bypass = DepositPolicy.bypassesDepositNotice(depositPolicy);
        List<QualifyingQuotation> out = new ArrayList<>();
        for (CustomerQuotationDto q : candidates) {
            if (!QuotationStatus.ACCEPTED.equals(q.docStatus())) continue;
            if (bypass) {
                out.add(new QualifyingQuotation(q, null));
                continue;
            }
            // notices is already DESC by version (DepositNoticeRepository#findByTicket) — the
            // first ISSUED hit whose reference matches is the latest matching one.
            DepositNoticeDto matched = notices.stream()
                .filter(n -> "ISSUED".equals(n.status()) && q.number().equals(n.reference()))
                .findFirst().orElse(null);
            if (matched != null) out.add(new QualifyingQuotation(q, matched));
        }
        out.sort(Comparator.comparingLong((QualifyingQuotation qq) -> qq.quotation().id()).reversed());
        return out;
    }

    /**
     * The single entry point both getRemainingInvoiceOptions and getRemainingInvoiceXlsx route
     * through. Resolution order:
     * <ol>
     *   <li>a caller-supplied {@code quotationId} always wins, but MUST be one of this ticket's
     *       own qualifying quotations (400 otherwise — never another ticket's quotation, and
     *       never one that failed to qualify);</li>
     *   <li>else the newest qualifying quotation, if any qualify;</li>
     *   <li>else, if an ACCEPTED quotation exists but none qualifies under a deposit-required
     *       policy (the deposit notice for it was never issued) — blocked, not a silent
     *       fallback;</li>
     *   <li>else the pre-PCR-chain legacy fallbacks (issued deposit-notice item snapshot, then
     *       {@code ticket_item.approved_price}) — back-compat for a deal that predates the
     *       pricing-request chain and never had a CustomerQuotation at all.</li>
     * </ol>
     */
    private ResolvedRemainingInvoice resolveRemainingInvoice(long ticketId, TicketDto ticket, TicketSummaryDto s,
            Long quotationId, List<CustomerQuotationDto> quotationCandidates, List<DepositNoticeDto> notices) {
        List<QualifyingQuotation> qualifying = findQualifyingQuotations(quotationCandidates, notices, s.depositPolicy());
        List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions = qualifying.size() > 1
            ? qualifying.stream()
                .map(qq -> new RemainingInvoiceOptionsDto.QuotationOption(qq.quotation().id(), quotationLabel(qq.quotation())))
                .toList()
            : List.of();
        Long defaultQuotationId = qualifying.isEmpty() ? null : qualifying.get(0).quotation().id();

        if (quotationId != null) {
            QualifyingQuotation chosen = qualifying.stream()
                .filter(qq -> qq.quotation().id() == quotationId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                    "ใบเสนอราคาที่เลือกไม่ผ่านเงื่อนไข หรือไม่ใช่ของดีลนี้"));
            return resolveFromQuotation(ticketId, chosen, quotationCandidates, quotationOptions, defaultQuotationId);
        }
        if (!qualifying.isEmpty()) {
            return resolveFromQuotation(ticketId, qualifying.get(0), quotationCandidates, quotationOptions, defaultQuotationId);
        }

        boolean bypass = DepositPolicy.bypassesDepositNotice(s.depositPolicy());
        if (!bypass) {
            CustomerQuotationDto latestAccepted = pickAcceptedQuotation(quotationCandidates);
            if (latestAccepted != null) {
                return blocked("ยังไม่ได้ออกใบแจ้งยอดมัดจำจากใบเสนอราคา " + latestAccepted.number());
            }
        }
        return resolveLegacy(ticketId, ticket, notices);
    }

    /** Item/deduction sourcing (owner ruling D11) and rule C (net must not be negative), applied
     * to the single chosen quotation — see this section's header comment for the full rules. */
    private ResolvedRemainingInvoice resolveFromQuotation(long ticketId, QualifyingQuotation chosen,
            List<CustomerQuotationDto> quotationCandidates,
            List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions, Long defaultQuotationId) {
        CustomerQuotationDto q = chosen.quotation();
        DepositNoticeDto matched = chosen.matchedNotice();

        List<RemainingInvoiceItemDto> items;
        BigDecimal depositAmount = BigDecimal.ZERO;
        String defaultDepositReference = null;
        List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions = List.of();
        if (matched != null) {
            // D11 finding 2 (2026-09-18): an ISSUED notice with a null/empty item snapshot is
            // refused outright, the same way resolveLegacy already refuses one (see its own
            // !latestIssued.items().isEmpty() guard) — NEVER silently rendered as a zero-total
            // invoice, and NEVER falls back to the quotation's own items either: under D11 the
            // matched notice is supposed to be the sole authority for items once matched, so
            // falling back to the quotation here would resurrect the exact sourcing ambiguity D11
            // was meant to remove.
            if (matched.items() == null || matched.items().isEmpty()) {
                return blockedWithOptions(
                    "ใบแจ้งยอดมัดจำที่จับคู่ไว้ " + nullSafe(matched.docNumber())
                        + " ไม่มีรายการสินค้า กรุณาตรวจสอบเอกสาร",
                    quotationOptions, defaultQuotationId);
            }
            // Ruling D11 (2026-09-17): items AND the deduction amount come from the matched
            // deposit notice's own latest ISSUED item snapshot — never the quotation's items. The
            // quotation only identified WHICH notice counts (the reference match in
            // findQualifyingQuotations above); an edited/re-issued notice at a different amount
            // than the quotation IS the agreed amount now, so there is nothing to compare/block on
            // here — the old subtotal-must-match rule (matched.subtotal() vs the quotation's own
            // item total) is deliberately gone.
            items = itemsFromDepositNoticeForRemainingInvoice(matched.items());
            depositAmount = matched.depositAmount() != null ? matched.depositAmount() : BigDecimal.ZERO;
            DepositRefBundle refs = resolveDepositReferenceOptions(ticketId, matched);
            defaultDepositReference = refs.defaultRef();
            depositReferenceOptions = refs.options();
        } else {
            // Bypass-policy case only (see findQualifyingQuotations/this section's header
            // comment): no deposit notice exists to source from, so items come from the accepted
            // quotation itself — the one case items are still quotation-sourced.
            items = itemsFromQuotationForRemainingInvoice(q.items());
        }
        BigDecimal itemsTotal = sumAmounts(items);

        // Rule C: refuse a negative invoice. Net == 0 is allowed (compareTo < 0 only).
        if (itemsTotal.subtract(depositAmount).compareTo(BigDecimal.ZERO) < 0) {
            return new ResolvedRemainingInvoice(items, depositAmount, q.number(),
                buildQuotationReferenceOptions(quotationCandidates).referenceOptions(), null, List.of(),
                quotationOptions, defaultQuotationId,
                "ยอดหลังหักมัดจำติดลบ (ยอดสินค้า " + fmt(itemsTotal) + " น้อยกว่ายอดมัดจำ " + fmt(depositAmount)
                    + " บาท) กรุณาตรวจสอบใบแจ้งยอดมัดจำหรือใบเสนอราคา " + q.number());
        }

        return new ResolvedRemainingInvoice(items, depositAmount, q.number(),
            buildQuotationReferenceOptions(quotationCandidates).referenceOptions(),
            defaultDepositReference, depositReferenceOptions, quotationOptions, defaultQuotationId, null);
    }

    /** Pre-PCR-chain back-compat only — reached exclusively when the ticket has NO accepted
     * CustomerQuotation at all (see resolveRemainingInvoice's own Javadoc). Preserves the exact
     * item-sourcing/reference/deposit-reference behaviour this document always had before the
     * quotation-selection ruling: the latest ISSUED deposit notice's own item snapshot, else the
     * legacy {@code ticket_item.approved_price} rows, else 409 — there is nothing to price. */
    private ResolvedRemainingInvoice resolveLegacy(long ticketId, TicketDto ticket, List<DepositNoticeDto> notices) {
        DepositNoticeDto latestIssued = notices.stream()
            .filter(n -> "ISSUED".equals(n.status()))
            .findFirst().orElse(null); // notices DESC by version already
        List<RemainingInvoiceItemDto> items;
        if (latestIssued != null && latestIssued.items() != null && !latestIssued.items().isEmpty()) {
            items = itemsFromDepositNoticeForRemainingInvoice(latestIssued.items());
        } else {
            items = legacyRemainingInvoiceItems(ticket);
            if (items.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ไม่พบใบเสนอราคาที่ลูกค้ายอมรับ หรือใบแจ้งยอดมัดจำที่ออกแล้วสำหรับดีลนี้ จึงไม่สามารถออกใบแจ้งหนี้ส่วนที่เหลือได้");
            }
        }
        BigDecimal depositAmount = latestIssued != null && latestIssued.depositAmount() != null
            ? latestIssued.depositAmount() : BigDecimal.ZERO;
        BigDecimal itemsTotal = sumAmounts(items);
        if (itemsTotal.subtract(depositAmount).compareTo(BigDecimal.ZERO) < 0) {
            return new ResolvedRemainingInvoice(items, depositAmount, null, List.of(), null, List.of(),
                List.of(), null,
                "ยอดหลังหักมัดจำติดลบ (ยอดสินค้า " + fmt(itemsTotal) + " น้อยกว่ายอดมัดจำ " + fmt(depositAmount) + " บาท)");
        }
        String defaultDepositReference = null;
        List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions = List.of();
        if (latestIssued != null) {
            DepositRefBundle refs = resolveDepositReferenceOptions(ticketId, latestIssued);
            defaultDepositReference = refs.defaultRef();
            depositReferenceOptions = refs.options();
        }
        return new ResolvedRemainingInvoice(items, depositAmount, null, List.of(),
            defaultDepositReference, depositReferenceOptions, List.of(), null, null);
    }

    private ResolvedRemainingInvoice blocked(String reason) {
        return new ResolvedRemainingInvoice(List.of(), BigDecimal.ZERO, null, List.of(), null, List.of(),
            List.of(), null, reason);
    }

    /** Same as {@link #blocked}, but also carries the quotation-picker options through — used when
     * the block happens INSIDE {@link #resolveFromQuotation} (a specific quotation was already
     * chosen), so the dialog can still offer switching to a different qualifying quotation instead
     * of losing the picker entirely. See D11 finding 2 (2026-09-18): an ISSUED notice matched with
     * a null/empty item snapshot. */
    private ResolvedRemainingInvoice blockedWithOptions(String reason,
            List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions, Long defaultQuotationId) {
        return new ResolvedRemainingInvoice(List.of(), BigDecimal.ZERO, null, List.of(), null, List.of(),
            quotationOptions, defaultQuotationId, reason);
    }

    private String quotationLabel(CustomerQuotationDto q) {
        String suffix = q.recipientLabel() != null && !q.recipientLabel().isBlank() ? q.recipientLabel()
            : (q.recipientType() != null && !q.recipientType().isBlank() ? q.recipientType() : null);
        return suffix != null ? q.number() + " (" + suffix + ")" : q.number();
    }

    private BigDecimal sumAmounts(List<RemainingInvoiceItemDto> items) {
        return items.stream()
            .map(it -> it.amount() != null ? it.amount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fmt(BigDecimal v) {
        return String.format(java.util.Locale.US, "%,.2f", v != null ? v : BigDecimal.ZERO);
    }

    /** H9 (reference) suggestion list: ACCEPTED quotations first (latest first), then ISSUED
     * (latest first) — a free-text datalist, so this stays the FULL candidate list regardless of
     * which one the deposit deduction actually came from (never "mixed", since the deposit itself
     * is scoped elsewhere — see resolveFromQuotation). */
    private record QuotationReferenceOptions(List<RemainingInvoiceOptionsDto.ReferenceOption> referenceOptions) {}

    private QuotationReferenceOptions buildQuotationReferenceOptions(List<CustomerQuotationDto> candidates) {
        List<CustomerQuotationDto> acceptedDesc = candidates.stream()
            .filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus()))
            .sorted(Comparator.comparingLong(CustomerQuotationDto::id).reversed())
            .toList();
        List<CustomerQuotationDto> issuedDesc = candidates.stream()
            .filter(q -> QuotationStatus.ISSUED.equals(q.docStatus()))
            .sorted(Comparator.comparingLong(CustomerQuotationDto::id).reversed())
            .toList();
        List<RemainingInvoiceOptionsDto.ReferenceOption> referenceOptions = new ArrayList<>();
        for (CustomerQuotationDto q : acceptedDesc) referenceOptions.add(new RemainingInvoiceOptionsDto.ReferenceOption(q.number(), q.number()));
        for (CustomerQuotationDto q : issuedDesc) referenceOptions.add(new RemainingInvoiceOptionsDto.ReferenceOption(q.number(), q.number()));
        return new QuotationReferenceOptions(referenceOptions);
    }

    /** Rule D: deposit-reference dropdown = receipt_refs of DEPOSIT payment_receipt rows LINKED to
     * the matched notice ({@code deposit_notice_id}) — falling back to ALL DEPOSIT receipts on the
     * ticket only when none are linked — plus the matched notice's own number, plus a blank
     * option. Default = the linked/fallback list's own latest receipt_ref, else the notice's
     * docNumber. */
    private record DepositRefBundle(String defaultRef, List<RemainingInvoiceOptionsDto.ReferenceOption> options) {}

    private DepositRefBundle resolveDepositReferenceOptions(long ticketId, DepositNoticeDto matched) {
        List<PaymentReceiptDto> allDeposits = tickets.findReceiptsByTicket(ticketId).stream()
            .filter(r -> "DEPOSIT".equals(r.kind()) && r.receiptRef() != null && !r.receiptRef().isBlank())
            .sorted(Comparator.comparingLong(PaymentReceiptDto::receiptId).reversed())
            .toList();
        List<PaymentReceiptDto> linked = allDeposits.stream()
            .filter(r -> r.depositNoticeId() != null && r.depositNoticeId() == matched.id())
            .toList();
        List<PaymentReceiptDto> source = !linked.isEmpty() ? linked : allDeposits;

        List<RemainingInvoiceOptionsDto.ReferenceOption> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PaymentReceiptDto r : source) {
            if (seen.add(r.receiptRef())) {
                options.add(new RemainingInvoiceOptionsDto.ReferenceOption(r.receiptRef(), r.receiptRef()));
            }
        }
        if (matched.docNumber() != null && !matched.docNumber().isBlank() && seen.add(matched.docNumber())) {
            options.add(new RemainingInvoiceOptionsDto.ReferenceOption(matched.docNumber(), matched.docNumber()));
        }
        options.add(new RemainingInvoiceOptionsDto.ReferenceOption("", "(ไม่ระบุ)"));
        String defaultRef = !source.isEmpty() ? source.get(0).receiptRef() : matched.docNumber();
        return new DepositRefBundle(defaultRef, options);
    }

    /** Latest ACCEPTED revision only — see {@link #resolveRemainingInvoice}'s own Javadoc for why
     * this deliberately does NOT fall back to the latest ISSUED-but-not-yet-accepted one the way
     * {@link #pickQuotation} does for the deposit notice. */
    private CustomerQuotationDto pickAcceptedQuotation(List<CustomerQuotationDto> candidates) {
        CustomerQuotationDto latestAccepted = null;
        for (CustomerQuotationDto q : candidates) {
            if (QuotationStatus.ACCEPTED.equals(q.docStatus())) latestAccepted = q;
        }
        return latestAccepted;
    }

    /** Finding 5 (preview/render agreement): the ONE shared line-amount function for a quotation
     * item, used both by the subtotal-match check above and by
     * {@link #itemsFromQuotationForRemainingInvoice}'s own {@code amount} field below —
     * {@code line_subtotal} is nullable (V74), so a null there must never silently diverge between
     * the options preview and the actual renderer input. Stored {@code lineSubtotal} wins when
     * present (the customer's own agreed figure — see quotation-arithmetic-reconciled: pieces are
     * HALF_UP, not ceil, so it can differ from a naive recompute by a rounding fraction); otherwise
     * HALF_UP(finalUnitPrice × qty). */
    private BigDecimal quotationLineAmount(CustomerQuotationItemDto item) {
        if (item.lineSubtotal() != null) return item.lineSubtotal();
        BigDecimal price = item.finalUnitPrice() != null ? item.finalUnitPrice() : BigDecimal.ZERO;
        BigDecimal qty = item.requestedQuantity() != null ? item.requestedQuantity() : BigDecimal.ZERO;
        return price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** Short discount-label form (finding 2b, and D11-finding-1 2026-09-18): {@code "ลด " + amount}
     * — the only form proven to fit the renderer's narrow column G without
     * {@link RemainingInvoiceRenderer#clampDiscountLabel} truncating it (see that method's own
     * budget comment). Blank when there is no discount. Shared by BOTH item-sourcing paths below
     * so the format can never drift between them — before this, {@link
     * #itemsFromDepositNoticeForRemainingInvoice} passed the deposit notice's own {@code
     * discountLabel} straight through, which {@code itemsFromQuotation}
     * (DepositNoticeService's static helper, used by both {@code createDraft} and
     * {@code OrderConfirmationService.createDepositNoticeFromQuotation}) authors in the LONG form
     * {@code "ส่วนลด " + amount + " ต่อหน่วย"} — that reintroduced the exact clipping this short
     * form was created to fix, for every notice-matched (the common) case. */
    private String shortDiscountLabel(BigDecimal discountPerUnit) {
        return discountPerUnit != null && discountPerUnit.signum() > 0
            ? "ลด " + discountPerUnit.stripTrailingZeros().toPlainString() : null;
    }

    private List<RemainingInvoiceItemDto> itemsFromQuotationForRemainingInvoice(List<CustomerQuotationItemDto> quotationItems) {
        List<RemainingInvoiceItemDto> out = new ArrayList<>();
        for (CustomerQuotationItemDto item : quotationItems) {
            String description = item.description() != null && !item.description().isBlank()
                ? item.description() : "รายการสินค้า";
            out.add(new RemainingInvoiceItemDto(
                item.seq(), description, item.requestedQuantity(), unitLabel(item.requestedUnitBasis()),
                item.approvedUnitPrice(), shortDiscountLabel(item.salesDiscount()), item.finalUnitPrice(),
                // The customer's own accepted subtotal — never recomputed as finalUnitPrice*qty
                // outright (see quotationLineAmount's own Javadoc for the null-handling rule).
                quotationLineAmount(item)));
        }
        return out;
    }

    private List<RemainingInvoiceItemDto> itemsFromDepositNoticeForRemainingInvoice(List<DepositNoticeItemDto> noticeItems) {
        List<RemainingInvoiceItemDto> out = new ArrayList<>();
        for (DepositNoticeItemDto item : noticeItems) {
            // D11 finding 1 (2026-09-18): normalize into the short form via shortDiscountLabel
            // above — do NOT pass item.discountLabel() through, it is authored in the long
            // "ส่วนลด X ต่อหน่วย" form (see itemsFromQuotation) and clips in column G. The notice
            // doesn't store a separate discount-amount field, so the per-unit discount is derived
            // from its own numeric unitPrice/netUnitPrice (unitPrice - netUnitPrice) rather than
            // string-parsing discountLabel. This mirrors how those two numbers were produced in
            // the first place: CustomerQuotationService#applyItemUpdates computes finalUnitPrice
            // as money4(approvedUnitPrice - discount) at NUMERIC(18,4), and itemsFromQuotation
            // maps approvedUnitPrice/finalUnitPrice onto this notice item's unitPrice/netUnitPrice
            // — but sales.deposit_notice_item stores those two columns at NUMERIC(15,2) (V12),
            // narrower than the 4dp the discount was computed at. So the subtraction here recovers
            // the discount consistent with what this notice's OWN 2dp columns show (matching the
            // remaining invoice's own printed E/H amounts) — not necessarily bit-for-bit the
            // original 4dp figure the quotation carried. A discount of 43.1725 on this notice
            // prints here as 43.17, same rounding the notice's unitPrice/netUnitPrice already went
            // through, not the sharper number a quotation-sourced remaining invoice would show.
            BigDecimal discountPerUnit = item.unitPrice() != null && item.netUnitPrice() != null
                ? item.unitPrice().subtract(item.netUnitPrice()) : null;
            out.add(new RemainingInvoiceItemDto(
                item.seq(), item.description(), item.qty(), item.unit(),
                item.unitPrice(), shortDiscountLabel(discountPerUnit), item.netUnitPrice(), item.amount()));
        }
        return out;
    }

    /** Legacy back-compat path — same shape {@code getRemainingInvoiceXlsx} always used before
     * this branch, kept for any ticket manually approved before the pricing-request chain
     * existed (approved_price set directly on ticket_item, no quotation ever created). */
    private List<RemainingInvoiceItemDto> legacyRemainingInvoiceItems(TicketDto ticket) {
        int[] seq = {1};
        return ticket.items().stream()
            .filter(it -> it.approvedPrice() != null)
            .map(it -> {
                String desc = java.util.stream.Stream.of(
                    it.brand(), it.model(), it.color(), it.texture(), it.size()
                ).filter(v -> v != null && !v.isBlank())
                 .reduce((a, b) -> a + " " + b).orElse(nullSafe(it.brand()));
                String unit = "SQM".equals(it.unitBasis()) ? "ตร.ม." : "แผ่น";
                BigDecimal qty = "SQM".equals(it.unitBasis()) && it.qtySqm() != null
                    ? it.qtySqm() : (it.qty() != null ? it.qty() : BigDecimal.ZERO);
                BigDecimal price = it.approvedPrice();
                BigDecimal amount = price.multiply(qty);
                return new RemainingInvoiceItemDto(seq[0]++, desc, qty, unit, price, null, price, amount);
            })
            .toList();
    }

    /** {@code noteIds == null} → the templates' own defaultSelected set (same default createDraft
     * uses); present-but-empty → no notes at all; otherwise exactly the requested ids, in the
     * templates' own sortOrder (never the caller-supplied order — this is a curated official list,
     * not free text). Never truncates — {@code sales.document_note_template} currently seeds
     * THREE rows (V16), not five/six, and the caller may select any subset; whether the selection
     * FITS the template's 6-line หมายเหตุ block is decided by {@link RemainingInvoiceRenderer#wrapNotes}
     * at the call site (getRemainingInvoiceXlsx/getRemainingInvoiceOptions), which refuses rather
     * than silently clips. */
    private List<String> resolveNotes(List<Long> noteIds) {
        List<DocumentNoteTemplateDto> templates = docs.findNoteTemplates();
        if (noteIds == null) {
            return templates.stream().filter(DocumentNoteTemplateDto::defaultSelected)
                .map(DocumentNoteTemplateDto::text).toList();
        } else if (noteIds.isEmpty()) {
            return List.of();
        }
        Set<Long> wanted = new LinkedHashSet<>(noteIds);
        return templates.stream().filter(t -> wanted.contains(t.id()))
            .map(DocumentNoteTemplateDto::text).toList();
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

    /**
     * Renders the issued document once the transaction that issued it has COMMITTED — or
     * immediately when no transaction is active. See {@link #issue}'s own comment for why the
     * render must not run inside the transaction.
     *
     * <p>Follows {@code FileStorageService#deleteOnCommit} and {@code
     * NotificationService#sendEmailAfterCommit}, the codebase's existing answer to "a non-database
     * side effect inside a {@code @Transactional} method", including the asymmetry: with no
     * transaction active there is no commit to wait for, so deferring would mean never rendering at
     * all. Both branches resolve to "do the work, unless a rollback could still take the row away".
     *
     * <p><b>The try/catch has to live in here, not around the call site.</b> An exception escaping a
     * {@link TransactionSynchronization#afterCommit} callback propagates to whoever called {@code
     * commit} — so a LibreOffice timeout would start failing an {@code issue()} that had already
     * durably succeeded. The swallow is therefore load-bearing now in a way it was not before, and
     * it preserves the pre-existing contract exactly: rendering is non-fatal, the flags are left
     * unset, and the bytes are regenerable on download.
     */
    private void renderAfterCommit(long docId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            renderIssuedDocuments(docId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                renderIssuedDocuments(docId);
            }
        });
    }

    /** Best-effort render + flag write that can never throw — see {@link #renderAfterCommit}. */
    private void renderIssuedDocuments(long docId) {
        try {
            DepositNoticeDto issued = docs.findById(docId).orElseThrow();
            renderer.toPdf(issued);
            renderer.toXlsx(issued);
            docs.setFilePaths(docId, "rendered", "rendered");
        } catch (Exception e) {
            // Non-fatal: files can be regenerated on download.
        }
    }

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

    /** Resolved customer-master header fields, shared by createDraft (deposit notice) and the
     * remaining-invoice methods below — see {@link #resolveCustomerHeader} for the lookup rule.
     * {@code branch} is new for this branch: createDraft never needed it standalone (it only ever
     * folds branch INTO the joined address string), but the remaining invoice's B7 line needs it
     * separately ("<name> (<branch>) / เลขประจำตัวผู้เสียภาษี : <taxId>"). {@code addressOnly} is
     * also new (finding 2d, branch-printed-twice): the remaining invoice's B8 must be the address
     * WITHOUT the branch folded in (B7 already shows it in parens) — {@code address} itself stays
     * exactly as createDraft has always built it (address+branch joined), since that document's
     * behaviour predates this branch and must not change. */
    private record CustomerHeaderInfo(String taxId, String address, String addressOnly, String branch, String projectName) {}

    /**
     * Single source of truth for "where does a document's customer header come from", used by
     * both createDraft (deposit notice) and getRemainingInvoiceOptions/getRemainingInvoiceXlsx
     * (remaining invoice) — extracted from createDraft's own pre-existing inline logic so the two
     * documents can never source a customer's address/tax id/branch differently. A caller-supplied
     * non-blank {@code reqTaxId}/{@code reqAddress}/{@code reqProjectName} always wins (createDraft
     * is the only caller that ever passes one — the remaining-invoice methods pass all-null,
     * meaning "always resolve from the customer master"); a null {@code customerId} (never linked
     * to a customer master row) safely leaves these fields blank rather than throwing.
     */
    private CustomerHeaderInfo resolveCustomerHeader(TicketSummaryDto s,
            String reqTaxId, String reqAddress, String reqProjectName) {
        String customerTaxId = blankToNull(reqTaxId);
        String customerAddress = blankToNull(reqAddress);
        String addressOnly = null;
        String projectName = blankToNull(reqProjectName);
        String branch = null;
        if ((customerTaxId == null || customerAddress == null) && s.customerId() != null) {
            CustomerDto customer = customers.findById(s.customerId()).orElse(null);
            if (customer != null) {
                branch = blankToNull(customer.branch());
                addressOnly = blankToNull(customer.address());
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
        if (addressOnly == null) {
            addressOnly = customerAddress;
        }
        if (projectName == null) {
            projectName = blankToNull(s.projectName());
        }
        return new CustomerHeaderInfo(customerTaxId, customerAddress, addressOnly, branch, projectName);
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
