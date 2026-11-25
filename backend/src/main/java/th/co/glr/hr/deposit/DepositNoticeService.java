package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import th.co.glr.hr.ticket.DealOwnership;
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
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.DealNoticeText;
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
    // No PREPARER constant here. Deleting the Java copy leaves that default — the only live source — untouched;
    // re-adding it would just create a second place to change a person's name and a silent way for the two to
    // disagree.
    private static final java.util.Set<String> SALES_ROLES  = java.util.Set.of("sales");
    private static final java.util.Set<String> CEO_ROLES    = java.util.Set.of("ceo");
    private static final java.util.Set<String> IMPORT_ROLES = java.util.Set.of("import");
    // Same read rule as TicketService.VIEWER_ROLES: deposit notices are customer financial documents — hr/employee
    // have no business downloading them. sales_manager is read-only oversight here too — never add it to
    // SALES_ROLES/ CEO_ROLES/IMPORT_ROLES.
    private static final java.util.Set<String> VIEWER_ROLES =
        java.util.Set.of("sales", "import", "ceo", "account", "sales_manager");

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DepositNoticeService.class);

    private final DepositNoticeRepository docs;
    private final TicketRepository   tickets;
    private final NotificationRepository notifications;
    private final DepositNoticePdfRenderer renderer;
    java.time.Clock clock = java.time.Clock.systemUTC(); // package-private: tests fix "now"
    private final CustomerRepository customers;
    private final CustomerQuotationRepository quotations;

    private final DocumentBundle bundle;
    private final RemainingInvoiceRepository remainingInvoices;

    private th.co.glr.hr.deliveryrequest.PrePortal prePortal;

    /** Setter-injected so hand-wired tests are unaffected. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wirePrePortal(th.co.glr.hr.deliveryrequest.PrePortal prePortal) {
        this.prePortal = prePortal;
    }

    /** An old (pre_portal) deal's order is confirmed by its first deposit notice or remaining invoice. */
    void ensureOrderConfirmed(long ticketId, UserPrincipal actor) {
        if (prePortal != null) {
            prePortal.ensureOrderConfirmed(ticketId, null, actor);
        }
    }

    public DepositNoticeService(DepositNoticeRepository docs, TicketRepository tickets,
                           NotificationRepository notifications, DepositNoticePdfRenderer renderer,
                           CustomerRepository customers,
                           CustomerQuotationRepository quotations, DocumentBundle bundle,
                           RemainingInvoiceRepository remainingInvoices) {
        this.bundle            = bundle;
        this.remainingInvoices = remainingInvoices;
        this.docs              = docs;
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.renderer          = renderer;
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

    public List<DocumentPreparerOption> documentPreparerOptions(long ticketId, UserPrincipal actor) {
        requireTicketViewer(ticketId, actor);
        return docs.findPreparerOptions(ticketId);
    }

    /** A non-null preparerId must be one of THIS deal's options (owner, keyer, sales managers), unless it is the
     * document's stored preparer (the UIs resend it on every save, even after the deal was re-owned). */
    public void requirePreparerInDeal(long ticketId, Long preparerId, Long storedPreparerId) {
        if (preparerId != null && !preparerId.equals(storedPreparerId)
                && docs.findPreparerOptions(ticketId).stream().noneMatch(o -> o.id() == preparerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ผู้จัดทำต้องเป็นเจ้าของงาน ผู้คีย์งาน หรือผู้จัดการฝ่ายขายของงานนี้");
        }
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
        return createDraftInternal(ticketId, req, null, actor, true, null);
    }

    /** {@link #createDraft} against the quotation the caller chose (a delivery request's chosen quotation); 409 when
     * that quotation cannot source a notice. */
    @Transactional
    public DepositNoticeDto createDraft(long ticketId, DepositNoticeDraftRequest req, Long quotationId, UserPrincipal actor) {
        return createDraftInternal(ticketId, req, null, actor, true, quotationId);
    }

    /** {@link #createDraft} for a caller that has already chosen the source quotation: a non-null
     * {@code sourceDepositPercent} (the whole-number percent that quotation's PDF printed) is the
     * draft's percent, and the ticket's governing quotation is not consulted. */
    @Transactional
    public DepositNoticeDto createDraftFromQuotation(long ticketId, DepositNoticeDraftRequest req,
                                                     Integer sourceDepositPercent, UserPrincipal actor) {
        return createDraftInternal(ticketId, req, sourceDepositPercent, actor, false, null);
    }

    /** {@code clientLines}: {@code req.items()} came from a client body (so {@link #echoOnly} distrusts them), not
     * from the server's own quotation. */
    private DepositNoticeDto createDraftInternal(long ticketId, DepositNoticeDraftRequest req,
                                                 Integer sourceDepositPercent, UserPrincipal actor,
                                                 boolean clientLines, Long chosenQuotationId) {
        requireRole(actor, SALES_ROLES);
        requireTicketOwner(ticketId, actor);
        ensureOrderConfirmed(ticketId, actor); // before the row lock: the confirm takes advisory then row
        // The ticket row lock BEFORE any state check, as DealQuotationService#undoOrderConfirmation holds it while it
        // deletes the deal's draft notices: without it a draft created concurrently survives the undo. Row lock only (the
        // undo takes the advisory lock first, then this one), so no lock order is reversed.
        tickets.lockTicketForUpdate(ticketId);
        // One deposit notice per deal. A new draft would re-seed the % from
        // the quotation and, once issued, silently supersede the customer's document.
        docs.findByTicket(ticketId).stream().filter(d -> "ISSUED".equals(d.status())).findFirst().ifPresent(d -> {
            throw new ApiException(HttpStatus.CONFLICT,
                "งานนี้ออกใบแจ้งรับมัดจำเลขที่ " + d.docNumber() + " แล้ว จึงสร้างฉบับร่างใหม่ไม่ได้");
        });
        TicketSummaryDto s = requireApprovedTicket(ticketId, actor);
        requireActiveLifecycle(s);
        // Both routes: a deal off the deposit path (a ไม่รับมัดจำ quotation, a waiver, a credit
        // customer) has no deposit to bill, and issue() would refuse the draft anyway.
        if (DepositPolicy.bypassesDepositNotice(s.depositPolicy())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ต้องเก็บมัดจำ (นโยบายมัดจำ "
                + s.depositPolicy() + ") จึงสร้างใบแจ้งรับมัดจำไม่ได้");
        }

        // Ticket route: the governing quotation (DepositNoticeRepository#findGoverningQuotation)
        // always supplies the percent. It supplies the lines, and with them the reference, unless
        // req.items or legacy approved_price rows already supply the lines.
        DepositNoticeRepository.GoverningQuotation governing = sourceDepositPercent == null
            ? (chosenQuotationId == null ? docs.findGoverningQuotation(ticketId)
                : docs.findGoverningQuotation(ticketId, chosenQuotationId)).orElse(null) : null;
        if (chosenQuotationId != null && governing == null && sourceDepositPercent == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคานี้ใช้ออกใบแจ้งรับมัดจำไม่ได้");
        }
        if (governing != null && governing.depositPercent().signum() == 0) {
            throw zeroDepositRefusal(governing.number());
        }
        List<DepositNoticeItemRequest> items = buildItemsFromRequest(req, ticketId);
        if (clientLines) items = echoOnly(items, List.of());
        boolean linesFromGoverning = items.isEmpty() && governing != null;
        if (linesFromGoverning) items = itemsFromGoverning(governing);
        List<String> notes = req.notes() != null ? req.notes()
            : docs.findNoteTemplates().stream()
                .filter(DocumentNoteTemplateDto::defaultSelected)
                .map(DocumentNoteTemplateDto::text)
                .toList();

        // Header autofill (this branch's fix): customerTaxId/customerAddress/projectName were never populated for a
        // deal created through the pricing-request chain — only customerName had a ticket-summary fallback.
        // The customer is the one the referenced quotation is addressed to (a deal can hold several BUYER parties).
        String headerReference = req.reference() == null && linesFromGoverning ? governing.number() : req.reference();
        // เรียน (feedback #74): a new version keeps the latest earlier notice's choice for the same quotation, else the
        // default (see chooseNames).
        IssueNames names = headerReference == null ? IssueNames.NONE : docs.findByTicket(ticketId).stream().findFirst()
            .filter(prev -> headerReference.equals(prev.reference())).map(DepositNoticeDto::names)
            .orElseGet(() -> chooseNames(null, IssueNames.Choice.KEEP, ticketId, headerReference));
        CustomerHeaderInfo header = resolveCustomerHeader(s, req.customerTaxId(), req.customerAddress(), req.projectName(),
            ticketId, headerReference, names.addresseeCustomerId());

        // The deposit percent is the one the
        // source quotation's PDF printed: a legacy quotation 50% (the constant its PDF prints), a
        // new-engine one its own deposit_percent, a blank one 30%. The PR route passes it as
        // sourceDepositPercent; the ticket route takes it from the governing quotation. On create,
        // req.depositPercent() is never read (DepositNoticeDraftRequest carries it for update's
        // round trip). 0.50 only when the ticket has no live quotation at all (e.g. a manually
        // approved legacy ticket with no quotation chain); see
        // DepositNoticeRepository#findGoverningQuotation for the pick. Sales may change the percent
        // on the DRAFT afterwards (update).
        BigDecimal effectiveDepositPercent = sourceDepositPercent != null
            ? BigDecimal.valueOf(sourceDepositPercent, 2)
            : governing != null ? governing.depositPercent() : new BigDecimal("0.50");
        String reference = req.reference() == null && linesFromGoverning ? governing.number() : req.reference();

        var effective = new DepositNoticeDraftRequest(
            header.customerNameOr(req.customerName() != null ? req.customerName() : s.customerName()),
            header.taxId(), header.address(),
            header.projectName(), reference,
            effectiveDepositPercent,
            notes, items, null, actor.id()
        );

        long docId = docs.createDraft(ticketId, effective, items, actor.name(), names);
        return docs.findById(docId).orElseThrow();
    }

    /** The 409 both deposit routes give a ไม่รับมัดจำ (0%) quotation: there is no deposit to bill. */
    public static ApiException zeroDepositRefusal(String quotationNumber) {
        return new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคา " + quotationNumber
            + " ระบุไม่มีมัดจำ (ชำระเต็มจำนวน) จึงออกใบแจ้งยอดเงินรับมัดจำไม่ได้");
    }

    @Transactional
    public DepositNoticeDto update(long docId, DepositNoticeDraftRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        DepositNoticeDto doc = requireEditable(docId);
        TicketSummaryDto summary = requireTicketOwner(doc.ticketId(), actor);
        requireActiveLifecycle(summary);
        requirePreparerInDeal(doc.ticketId(), req.preparerId(), doc.preparerId());
        // Sales may change the deposit percent on a DRAFT or an ISSUED notice (a SUPERSEDED one is refused by
        // requireEditable above). docs.update recomputes the amount from it. A request without a percent keeps the
        // notice's own.
        BigDecimal depositPercent = req.depositPercent() != null ? req.depositPercent() : doc.depositPercent();
        if (depositPercent.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เปอร์เซ็นต์มัดจำต้องมากกว่า 0");
        }
        if (depositPercent.compareTo(BigDecimal.ONE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เปอร์เซ็นต์มัดจำต้องไม่เกิน 100");
        }
        // Whole percents only: the document prints the percent rounded (40.5% shows as "41%"). A
        // percent stored before that rule (0.3330) still saves while it is sent back unchanged;
        // issue() refuses it until sales picks a whole one.
        if (depositPercent.compareTo(doc.depositPercent()) != 0 && !isWholePercent(depositPercent)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เปอร์เซ็นต์มัดจำต้องเป็นจำนวนเต็ม");
        }
        // The customer always comes from the quotation the STORED reference names (a new-engine quotation with a
        // party): typed customer fields are ignored and the reference is kept, since the read-time join keys on it.
        String reference = doc.reference();
        CustomerHeaderInfo quotationHeader = resolveCustomerHeader(summary, null, null, null, doc.ticketId(), reference);
        boolean fromQuotation = quotationHeader.customerName() != null;
        String requested = blankToNull(req.reference());
        List<DepositNoticeItemRequest> items = echoOnly(req.items(), doc.items());
        boolean repointed = false;
        if (fromQuotation && requested != null && !requested.equals(reference)) {
            // Re-pointing: a DRAFT may move to another party quotation of THIS deal in a status create would bill
            // (findRepointTarget); anything else is refused, never silently ignored.
            var target = "DRAFT".equals(doc.status()) ? docs.findRepointTarget(doc.ticketId(), requested).orElse(null) : null;
            if (target == null) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "เปลี่ยนใบเสนอราคาอ้างอิงได้เฉพาะใบแจ้งยอดที่ยังเป็นฉบับร่าง และต้องเป็นใบเสนอราคาที่ใช้ออกใบแจ้งยอดได้ของดีลนี้");
            }
            if (target.depositPercent().signum() == 0) throw zeroDepositRefusal(requested);
            // lines and percent follow the new quotation (as create builds them); client-sent items are ignored
            items = docs.findDealQuotationItems(target.quotationId());
            if (items.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคา " + requested + " ไม่มีรายการสินค้า จึงเปลี่ยนมาอ้างอิงไม่ได้");
            }
            depositPercent = target.depositPercent();
            reference = requested;
            quotationHeader = resolveCustomerHeader(summary, null, null, null, doc.ticketId(), reference);
            repointed = true;
        }
        // เรียน (feedback #74): the quotation's customer by default, or any customer the rep picks or creates. A re-point is
        // a new buyer's document, so it starts from that quotation's default, with this save's choice on top.
        IssueNames names = chooseNames(repointed ? null : doc.names(), req.nameChoice(), doc.ticketId(), reference);
        requireNamesUnbilled("DEPOSIT_NOTICE", docId, doc.names(), names, "ใบแจ้งยอด");
        if (names.addresseeCustomerId() != null) {
            quotationHeader = resolveCustomerHeader(summary, null, null, null, doc.ticketId(), reference,
                names.addresseeCustomerId());
        }
        boolean fromMaster = fromQuotation || names.addresseeCustomerId() != null;
        var effective = new DepositNoticeDraftRequest(
            fromMaster ? quotationHeader.customerName() : req.customerName(),
            fromMaster ? quotationHeader.taxId() : req.customerTaxId(),
            fromMaster ? quotationHeader.address() : req.customerAddress(),
            req.projectName(), fromQuotation ? reference : req.reference(),
            depositPercent,
            req.notes(), items, req.expectedUpdatedAt(), req.preparerId()
        );
        docs.update(docId, effective, doc.items(), doc.status(), names);
        DepositNoticeDto updated = docs.findById(docId).orElseThrow();
        if ("ISSUED".equals(doc.status())) {
            BigDecimal netReceived = tickets.netDepositReceived(doc.ticketId());
            if (updated.depositAmount().compareTo(doc.depositAmount()) < 0 && updated.depositAmount().compareTo(
                    netReceived.divide(BigDecimal.ONE.add(VAT_RATE), 2, RoundingMode.HALF_UP)) < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "ลดยอดมัดจำต่ำกว่าที่รับชำระแล้วไม่ได้ (รับแล้ว ฿"
                    + String.format(java.util.Locale.US, "%,.2f", netReceived) + ") — ให้ฝ่ายบัญชีบันทึกคืนเงินก่อน");
            }
            tickets.addEvent(doc.ticketId(), actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
                "แก้ไขใบแจ้งรับมัดจำ " + doc.docNumber() + " (ออกเลขแล้ว): " + changes(doc, updated));
            if (doc.depositAmount().compareTo(updated.depositAmount()) != 0) {
                // account matches the bank transfer against this amount, same channel as issue()
                notifications.notifyByRole("account", doc.ticketId(), TicketEventKind.EDITED,
                    "แก้ไขใบแจ้งรับมัดจำ " + doc.docNumber() + " ยอดมัดจำ " + ThaiText.money(doc.depositAmount())
                        + " → " + ThaiText.money(updated.depositAmount()) + " บาท"
                        + " (ยอดชำระรวม VAT " + ThaiText.money(doc.totalPayable()) + " → "
                        + ThaiText.money(updated.totalPayable()) + " บาท) (ออกเลขแล้ว)");
            }
            refreshRemainingInvoices(doc.ticketId(), actor);
        }
        return updated;
    }

    /** Old -> new for what an in-place edit of an ISSUED notice changed (the audit message). */
    private static String changes(DepositNoticeDto before, DepositNoticeDto after) {
        List<String> parts = new ArrayList<>();
        if (before.depositPercent().compareTo(after.depositPercent()) != 0) {
            parts.add("มัดจำ " + percentText(before.depositPercent()) + "% → " + percentText(after.depositPercent()) + "%");
        }
        changed(parts, "อ้างอิง", before.reference(), after.reference());
        changed(parts, "ชื่อลูกค้า", before.customerName(), after.customerName());
        changed(parts, "เลขผู้เสียภาษี", before.customerTaxId(), after.customerTaxId());
        changed(parts, "ที่อยู่", before.customerAddress(), after.customerAddress());
        changed(parts, "โครงการ", before.projectName(), after.projectName());
        changed(parts, "หมายเหตุ", String.join(" / ", before.notes()), String.join(" / ", after.notes()));
        if (!sameLines(before.items(), after.items())) {
            parts.add("รายการสินค้าเปลี่ยน (" + before.items().size() + " → " + after.items().size() + " รายการ)");
        }
        if (before.subtotal().compareTo(after.subtotal()) != 0) {
            parts.add("ยอดสินค้า " + ThaiText.money(before.subtotal()) + " → " + ThaiText.money(after.subtotal()));
        }
        if (before.depositAmount().compareTo(after.depositAmount()) != 0) {
            parts.add("เงินมัดจำ " + ThaiText.money(before.depositAmount()) + " → " + ThaiText.money(after.depositAmount()));
        }
        return parts.isEmpty() ? "ไม่มีการเปลี่ยนแปลงค่าหลัก" : String.join(" · ", parts);
    }

    private static void changed(List<String> parts, String label, String before, String after) {
        if (!java.util.Objects.equals(nullToEmpty(before), nullToEmpty(after))) {
            parts.add(label + " \"" + nullToEmpty(before) + "\" → \"" + nullToEmpty(after) + "\"");
        }
    }

    /** Line equality by content (the ids are new on every save). */
    private static boolean sameLines(List<DepositNoticeItemDto> a, List<DepositNoticeItemDto> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            DepositNoticeItemDto x = a.get(i), y = b.get(i);
            if (!java.util.Objects.equals(x.description(), y.description()) || !java.util.Objects.equals(x.unit(), y.unit())
                    || !java.util.Objects.equals(x.discountLabel(), y.discountLabel())
                    || x.qty().compareTo(y.qty()) != 0 || x.unitPrice().compareTo(y.unitPrice()) != 0
                    || x.netUnitPrice().compareTo(y.netUnitPrice()) != 0 || x.amount().compareTo(y.amount()) != 0) {
                return false;
            }
        }
        return true;
    }

    private static String percentText(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /**
     * The stored remaining invoices (DRAFT and ISSUED, never SUPERSEDED) snapshot their deposit deduction and money
     * totals from the latest ISSUED notice, so an edit of that notice re-runs the same resolution they were built
     * with. An ISSUED invoice whose figures change gets an EDITED event on the deal (number, old -> new total and
     * deduction). A refusal of that resolution (blockingReason, an ApiException such as a deal that left
     * quotation_issued, or a row that left its status meanwhile) skips the invoice, is logged and leaves an EDITED
     * event saying it was NOT refreshed and why — it never fails the notice edit.
     */
    private void refreshRemainingInvoices(long ticketId, UserPrincipal actor) {
        for (RemainingInvoiceDocumentDto inv : remainingInvoices.findByTicket(ticketId)) {
            if (!"DRAFT".equals(inv.status()) && !"ISSUED".equals(inv.status())) continue;
            String label = inv.docNumber() == null ? "ฉบับร่าง" : inv.docNumber();
            String skipReason = null;
            try {
                RemainingInvoiceSnapshot snap = resolveRemainingInvoiceSnapshot(ticketId, inv.customerQuotationId(), actor,
                    inv.names(), IssueNames.Choice.KEEP);
                ResolvedRemainingInvoice resolved = snap.resolved();
                if (resolved.blockingReason() != null) {
                    skipReason = resolved.blockingReason();
                } else {
                    RemainingInvoiceMoney money = computeRemainingInvoiceMoney(resolved);
                    CustomerHeaderInfo header = snap.header();
                    BigDecimal deduction = resolved.depositAmount() == null ? BigDecimal.ZERO : resolved.depositAmount();
                    int rows = remainingInvoices.updateSnapshot(inv.id(), resolved.matchedDepositNoticeId(),
                        header.customerNameOr(snap.ticketSummary().customerName()), header.taxId(), header.branch(), header.addressOnly(),
                        header.projectName(), resolved.itemsTotal(), deduction, money.netAmount(), money.vatAmount(),
                        money.grandTotal(), inv.status());
                    if (rows == 0) {
                        skipReason = "สถานะเอกสารเปลี่ยนไประหว่างแก้ไข";
                    } else {
                        remainingInvoices.replaceItems(inv.id(), resolved.items());
                        if ("ISSUED".equals(inv.status())
                                && (inv.grandTotal().compareTo(money.grandTotal()) != 0
                                    || inv.depositDeduction().compareTo(deduction) != 0)) {
                            tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
                                "ใบแจ้งหนี้ส่วนที่เหลือ " + label + " อัปเดตตามใบแจ้งรับมัดจำที่แก้ไข: ยอดชำระ "
                                    + ThaiText.money(inv.grandTotal()) + " → " + ThaiText.money(money.grandTotal())
                                    + " · หักมัดจำ " + ThaiText.money(inv.depositDeduction()) + " → " + ThaiText.money(deduction));
                            if (inv.grandTotal().compareTo(money.grandTotal()) != 0) {
                                boolean noVat = money.vatAmount() != null && money.vatAmount().signum() == 0;
                                notifications.notifyByRoleAtLink("account", TicketEventKind.EDITED,
                                    "ใบแจ้งหนี้ส่วนที่เหลือ " + label + " อัปเดตตามใบแจ้งรับมัดจำที่แก้ไข\n"
                                        + DealNoticeText.dealLabel(snap.ticketSummary().code(), header.customerNameOr(snap.ticketSummary().customerName()))
                                        + " · ยอดชำระรวม " + (noVat ? "" : "VAT ") + ThaiText.money(inv.grandTotal())
                                        + " → " + ThaiText.money(money.grandTotal()) + " บาท",
                                    "/finance/deals/" + ticketId);
                            }
                        }
                    }
                }
            } catch (ApiException e) {
                skipReason = e.getMessage();
            }
            if (skipReason != null) {
                LOG.warn("Remaining invoice {} not refreshed after deposit notice edit: {}", inv.id(), skipReason);
                tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
                    "ใบแจ้งหนี้ส่วนที่เหลือ " + label + " ไม่ได้อัปเดตตามใบแจ้งรับมัดจำที่แก้ไข: " + skipReason);
            }
        }
    }

    // Returns HTML preview (PDF requires LibreOffice — mock for now)
    public String preview(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toPreviewHtml(doc, bangkokToday());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "แสดงตัวอย่างเอกสารไม่สำเร็จ: " + e.getMessage());
        }
    }

    // Issue: assign doc number, freeze document, transition ticket to document_issued
    // Without a stamp. @Transactional here too: a self-call from this overload would bypass the proxy.
    @Transactional
    public DepositNoticeDto issue(long docId, UserPrincipal actor) {
        return issue(docId, null, actor);
    }

    @Transactional
    public DepositNoticeDto issue(long docId, java.time.OffsetDateTime expectedUpdatedAt, UserPrincipal actor) {
        DepositNoticeDto doc = requireDraft(docId);
        requireRole(actor, SALES_ROLES);
        requireTicketOwner(doc.ticketId(), actor);
        if (doc.reference() != null && !doc.reference().isBlank()
                && docs.referencesUnbillableQuotation(doc.ticketId(), doc.reference())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาอ้างอิง " + doc.reference()
                + " ถูกยกเลิกหรือใช้ออกใบแจ้งยอดไม่ได้แล้ว — เลือกใบเสนอราคาอื่นที่ใช้งานอยู่แล้วจึงออกเอกสาร");
        }
        // The stored percent is what the document prints: outside a whole 1-100 (a 0% ticket-route
        // draft, a 0.3330 typed before whole percents) it takes no number until fixed on the draft.
        BigDecimal pct = doc.depositPercent();
        if (pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0 || !isWholePercent(pct)) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบแจ้งรับมัดจำนี้มีเปอร์เซ็นต์มัดจำ "
                + pct.movePointRight(2).stripTrailingZeros().toPlainString()
                + "% จึงออกเอกสารไม่ได้ (ต้องเป็นจำนวนเต็ม 1–100)");
        }

        // Issuing the deposit-notice DOCUMENT is the payment-track step: it requires a
        // customer-confirmed quotation and advances paymentStatus, leaving the main
        // status at quotation_issued. It must not flip the ticket to document_issued —
        // that side effect kills the dual-track UI and lets unpaid tickets close.
        TicketSummaryDto s = tickets.findById(doc.ticketId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"))
            .summary();
        // A deposit notice may be
        // (re-)issued from CUSTOMER_CONFIRMED (first issue) OR from DEPOSIT_NOTICE_ISSUED itself
        // (a revision — PaymentTrack's one legal self-loop; docs.issue's own "AND status='DRAFT'"
        // guard is what makes a revision safe to mint a fresh doc number here).
        //
        // The explicit !bypassesDepositNotice(...) guard matters beyond the paymentStatus check
        // above: DEPOSIT_NOTICE_ISSUED is not on a bypass policy's PaymentTrack path AT ALL, so a
        // WAIVED/NOT_REQUIRED/CREDIT_CUSTOMER deal that somehow reached CUSTOMER_CONFIRMED (the
        // paymentStatus check alone would pass) must still be refused HERE, with a clean 409 —
        // not left to fall through to advancePaymentStatus below, which would throw an uncaught
        // IllegalStateException (ApiExceptionHandler has no handler for it, so it would surface as
        // an opaque 500, not a controlled conflict). A bypass-policy deal CAN still hold a DRAFT
        // deposit notice (one drafted while the deal was REQUIRED survives a later waiver), so this
        // is a genuinely reachable path. On a bypass policy, issuing a deposit notice throws.
        boolean paymentTrackReady = !DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && ("CUSTOMER_CONFIRMED".equals(s.paymentStatus()) || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus()));
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status()) || !paymentTrackReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ออกใบแจ้งรับมัดจำได้เฉพาะเมื่อออกใบเสนอราคาแล้วและลูกค้ายืนยันคำสั่งซื้อแล้วเท่านั้น");
        }
        // A 0-baht draft (e.g. a ticket-route draft on a deal whose quotation says ไม่รับมัดจำ but
        // whose policy predates the confirmation sync) must not take a document number.
        if (doc.depositAmount() == null || doc.depositAmount().signum() <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบแจ้งรับมัดจำนี้มียอดมัดจำ 0 บาท จึงออกเอกสารไม่ได้");
        }
        // The guard below is check-then-act: lock the deal row first so two drafts issued at once
        // serialize, and the second one sees the first as ISSUED.
        tickets.lockTicketForUpdate(doc.ticketId());
        // One deposit notice per deal. A second draft (written before the
        // first was issued) must not silently supersede the customer's document.
        docs.findByTicket(doc.ticketId()).stream()
            .filter(d -> d.id() != docId && "ISSUED".equals(d.status())).findFirst().ifPresent(d -> {
                throw new ApiException(HttpStatus.CONFLICT,
                    "งานนี้ออกใบแจ้งรับมัดจำเลขที่ " + d.docNumber() + " แล้ว จึงออกฉบับร่างนี้ไม่ได้");
            });
        // Issuing advances the payment track — a paused/terminal deal must not move (Phase 1 lifecycle gate; mirrors
        // TicketService.requireActive).
        requireActiveLifecycle(s);

        String docNumber = docs.issue(docId, actor.id(), actor.name(), expectedUpdatedAt)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "ใบแจ้งรับมัดจำนี้ถูกออกไปแล้วโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง"));

        // Render downloadable files at issue time — but AFTER this transaction commits, never inside it. Safe to defer
        // because the render participates in no invariant here.
        renderAfterCommit(docId);

        // expected = s.paymentStatus(), which by the guard above is exactly CUSTOMER_CONFIRMED (first issue, single
        // hop) or DEPOSIT_NOTICE_ISSUED (revision, the one legal self-loop).
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

        // account@glr.co.th is notified at exactly this one
        // sales-pipeline moment — issuing (or re-issuing) a deposit notice is when account gets
        // work (watch for the payment, then confirm it). Deliberately called DIRECTLY inside this
        // @Transactional method, never wrapped in this class's own afterCommit helper
        // (renderAfterCommit just above uses one, for the PDF/XLSX render only): notifyByRole's
        // own mail dispatch already defers itself to after-commit inside SalesNotificationMailRouter
        // via AfterCommit.run, and a SECOND TransactionSynchronization registered from inside that
        // first callback is silently dropped by Spring 7 -- the in-app row would still land but the
        // email never would. See NotificationRepository/SalesNotificationMailRouter/AfterCommit's
        // own Javadoc; this exact trap already bit DealQuotationService.submitDraftRow once.
        //
        // isRevision reads s.paymentStatus() captured ABOVE, before advancePaymentStatus mutates the
        // row -- by the paymentTrackReady guard above it is exactly CUSTOMER_CONFIRMED (first issue)
        // or DEPOSIT_NOTICE_ISSUED (a revision re-issue, PaymentTrack's one legal self-loop), so this
        // is a cheap, already-in-hand signal rather than a second query.
        boolean isRevision = "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus());
        // Two details:
        //  1. Same "ไม่ระบุลูกค้า" fallback TicketService#notifySalesManagerOfRepDeclaration uses
        //     -- a deposit notice's customerName CAN be blank (see resolveCustomerHeader
        //     / createDraft's own comments on the new pricing-request chain), and a raw blank in the
        //     sentence reads as a rendering bug rather than "no name on file".
        //  2. Account matches this notice against a BANK TRANSFER, which is the deposit PLUS 7% VAT
        //     (doc.totalPayable()) -- not the pre-VAT depositAmount alone. Both figures come straight
        //     off the `doc` snapshot fetched by requireDraft() above the precondition checks, which
        //     docs.issue() does not touch (it only writes doc_number/status/issued_by/updated_at), so
        //     this is exactly what DepositNoticePdfRenderer prints as "ขอรับเงินมัดจำ"/"รวมเป็นเงินที่
        //     ต้องชำระ" -- no new arithmetic. The title (TICKET_EVENT_TITLES' DEPOSIT_NOTICE_ISSUED
        //     entry) already ends "รอยืนยันรับชำระ", so the message itself does not repeat it.
        String accountCustomerName = blankToNull(doc.customerName()) == null
            ? "ไม่ระบุลูกค้า" : doc.customerName().trim();
        notifications.notifyByRole("account", doc.ticketId(), TicketEventKind.DEPOSIT_NOTICE_ISSUED,
            "ออกใบแจ้งรับมัดจำ " + docNumber + (isRevision ? " (ฉบับแก้ไข)" : "") + " แล้ว ดีล " + s.code()
                + " ลูกค้า " + accountCustomerName + " ยอดมัดจำ " + ThaiText.money(doc.depositAmount()) + " บาท"
                + " (ยอดชำระรวม VAT " + ThaiText.money(doc.totalPayable()) + " บาท)");

        return docs.findById(docId).orElseThrow();
    }

    /** The STORED notice as the renderer prints it: only stored fields (createDraft already appended the customer's
     * branch to the stored address, so it prints there once). A DRAFT has no issue date yet and prints {@code today}, and
     * prints ฉบับร่าง in the number cell (presentation only; nothing stored changes) so the file cannot pass for issued. */
    DepositNoticeRenderModel renderModel(DepositNoticeDto doc, LocalDate today) {
        // a DRAFT file (ฉบับร่าง) never carries a real signature
        var sig = "DRAFT".equals(doc.status()) ? java.util.Optional.<th.co.glr.hr.dealquotation.EmployeeSignatureRepository.SignatureImage>empty()
            : docs.findPreparerSignature(doc.preparerId());
        return new DepositNoticeRenderModel(doc.issueDate() != null ? doc.issueDate() : today,
            doc.docNumber() != null ? doc.docNumber() : "ฉบับร่าง",
            doc.customerName(), null, doc.customerTaxId(), doc.customerAddress(), doc.projectName(), doc.reference(),
            doc.items(), doc.subtotal(), doc.depositPercent(), doc.depositAmount(), doc.vatAmount(), doc.totalPayable(),
            doc.preparerName(),
            sig.map(x -> x.image()).orElse(null));
    }

    /** The chosen preparer's signature image, for the remaining invoice's render model. */
    java.util.Optional<th.co.glr.hr.dealquotation.EmployeeSignatureRepository.SignatureImage> preparerSignature(Long employeeId) {
        return docs.findPreparerSignature(employeeId);
    }

    // Download Excel bytes
    public byte[] getXlsx(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toXls(renderModel(doc, bangkokToday()));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ Excel ไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** The notice's own PDF, without the bundled PO and quotation (a delivery round stores each document by itself). */
    public byte[] getPdf(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            return renderer.toPdf(renderModel(doc, bangkokToday()));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ PDF ไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** The PDF with the remembered (else default) attachment choice; the sheet's PUT is what changes it. */
    public DocumentBundle.Result getPdfBundle(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = getById(docId, actor);
        try {
            byte[] pdf = renderer.toPdf(renderModel(doc, bangkokToday()));
            return bundle.bundle(pdf, doc.ticketId(), doc.reference(), actor, "DEPOSIT_NOTICE", docId,
                readsOnlyByGrant(doc.ticketId(), actor));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "สร้างไฟล์ PDF ไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** What the download sheet should show for this notice: the remembered choice, else the defaults. */
    public DocumentBundle.Selection bundleSelection(long docId, UserPrincipal actor) {
        return bundleSelection("DEPOSIT_NOTICE", docId, getById(docId, actor).ticketId(), actor);
    }

    /** The download sheet's choice, remembered for this notice: writers only (the choice is the owner's, a reader's
     * download must not overwrite it). The lifecycle is not checked: it is a preference, not a change to the deal. */
    @Transactional
    public void saveBundleSelection(long docId, UserPrincipal actor, DocumentBundle.Selection selection) {
        saveBundleSelection("DEPOSIT_NOTICE", docId, getById(docId, actor).ticketId(), actor, selection);
    }

    @Transactional
    void saveBundleSelection(String kind, long documentId, long ticketId, UserPrincipal actor, DocumentBundle.Selection selection) {
        if (!mayWriteDealDocuments(requireTicketViewer(ticketId, actor).summary(), actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        bundle.saveSelection(kind, documentId, ticketId, selection);
    }

    DocumentBundle.Selection bundleSelection(String kind, long documentId, long ticketId, UserPrincipal actor) {
        return bundle.selection(kind, documentId, ticketId, readsOnlyByGrant(ticketId, actor));
    }

    /** The bundle (PO + quotation) for a download of a document of {@code ticketId}. */
    DocumentBundle.Result bundled(byte[] pdf, long ticketId, String quotationNumber, UserPrincipal actor,
                                  String kind, long documentId) {
        return bundle.bundle(pdf, ticketId, quotationNumber, actor, kind, documentId, readsOnlyByGrant(ticketId, actor));
    }

    // ── Remaining Invoice (ข้อ 13.5) ─────────────────────────────────────────
    //
    // Stateless: no table, no migration. Every field of the remaining-invoice options is
    // recomputed fresh on each call from the ticket's own data — the dialog's "options" preview
    // and the actual "/file" download MUST agree, so both funnel through the same
    // resolveRemainingInvoice pipeline (and its shared helpers: resolveCustomerHeader,
    // itemsFromQuotationForRemainingInvoice, itemsFromDepositNoticeForRemainingInvoice,
    // quotationLineAmount, resolveDepositReferenceOptions) rather than each re-deriving defaults
    // their own way.
    //
    // Quotation selection. A deal can carry several pricing requests
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
    // Item/deduction SOURCE (rule D11; the QUALIFICATION rules (a)/(b) just above only decide
    // which quotation counts, not where the ITEMS and the deduction amount come from). When a quotation qualifies via (b) — a
    // matched ISSUED deposit notice — the remaining invoice's item rows AND its deposit deduction
    // both come from that matched notice's OWN latest ISSUED item snapshot
    // (itemsFromDepositNoticeForRemainingInvoice), never from the quotation's own items. The
    // quotation only identifies WHICH deposit notice counts (via the reference match above); it
    // does not supply the rows. There is no "matched notice's subtotal must equal the
    // quotation's own item total" rule (see resolveFromQuotation) — a deposit notice can
    // be edited/re-issued at a different amount than the quotation, and that edited figure IS the
    // agreed amount, not a mismatch to block on. The negative-net refusal (rule C below)
    // applies, computed off the notice's own items. When a quotation qualifies via (a) — a bypass
    // policy with no deposit notice to source from — items come from the quotation itself
    // (itemsFromQuotationForRemainingInvoice); that is the ONLY case items are
    // quotation-sourced. resolveLegacy's back-compat path (reached only
    // when there is no accepted quotation at all) sources items from a deposit notice's own
    // snapshot the same way, so both paths share one item-sourcing convention.

    // Package-private (not private): reused as-is by RemainingInvoiceService for the STORED
    // remaining-invoice snapshot's own VAT line — one constant, never
    // two copies that could silently disagree.
    static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    /** One money bundle: net (items total minus the deposit deduction), VAT at {@link #VAT_RATE},
     * and the VAT-inclusive grand total — every rounding step {@code setScale(2, HALF_UP)}.
     * Shared so {@code RemainingInvoiceService}'s own
     * createDraft/updateDraft/issue do not each carry a separate copy of these same
     * three lines — one formula, called four times (this class's own preview, plus those three),
     * never four chances for the copies to drift apart. Package-private (not private) for that
     * reuse. */
    record RemainingInvoiceMoney(BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grandTotal) {}

    RemainingInvoiceMoney computeRemainingInvoiceMoney(BigDecimal itemsTotal, BigDecimal depositAmount) {
        return computeRemainingInvoiceMoney(itemsTotal, depositAmount, VAT_RATE);
    }

    /** The money of a resolved invoice: its own VAT rate (EN = 0) and items total (the quotation's stored total). */
    RemainingInvoiceMoney computeRemainingInvoiceMoney(ResolvedRemainingInvoice r) {
        return computeRemainingInvoiceMoney(r.itemsTotal(), r.depositAmount() == null ? BigDecimal.ZERO : r.depositAmount(),
            r.vatRate() != null ? r.vatRate() : VAT_RATE);
    }

    RemainingInvoiceMoney computeRemainingInvoiceMoney(BigDecimal itemsTotal, BigDecimal depositAmount, BigDecimal vatRate) {
        BigDecimal netAmount = itemsTotal.subtract(depositAmount).setScale(2, RoundingMode.HALF_UP);
        // Each tax invoice's VAT = round2(its own net x 7%) -- usual Thai practice;
        // a satang gap against the deal's payable is normal and outstanding follows the ISSUED documents.
        BigDecimal vatAmount = netAmount.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grandTotal = netAmount.add(vatAmount).setScale(2, RoundingMode.HALF_UP);
        return new RemainingInvoiceMoney(netAmount, vatAmount, grandTotal);
    }

    /** Prefill + preview for the download dialog. */
    public RemainingInvoiceOptionsDto getRemainingInvoiceOptions(long ticketId, UserPrincipal actor) {
        return getRemainingInvoiceOptions(ticketId, null, actor);
    }

    /** quotationId: optional live-preview selector — when several quotations qualify, the caller may ask for a
     * specific one's preview (must be one of THIS ticket's qualifying quotations; null previews the newest
     * qualifying quotation, i.e. defaultQuotationId). */
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
        BigDecimal itemsTotal = resolved.itemsTotal();
        RemainingInvoiceMoney money = computeRemainingInvoiceMoney(resolved);
        BigDecimal netAmount = money.netAmount();
        BigDecimal vatAmount = money.vatAmount();
        BigDecimal totalPayable = money.grandTotal();

        String blockingReason = resolved.blockingReason();

        return new RemainingInvoiceOptionsDto(
            buildRemainingInvoiceDocNumber(ticketId),
            bangkokToday(),
            resolved.defaultReference(),
            resolved.referenceOptions(),
            resolved.defaultDepositReference(),
            resolved.depositReferenceOptions(),
            getNoteTemplates(),
            // Counts the deposit-deduction row too (when it will render).
            items.size() + depositRowCount,
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

    // There is no stateless GET .../remaining-invoice/file route and no getRemainingInvoiceXlsx:
    // only an ISSUED/SUPERSEDED STORED remaining invoice is downloadable, via
    // RemainingInvoiceService#file, which renders a FROZEN snapshot rather than recomputing this
    // same resolution live on every call. Capacity/notes-capacity/negative-net
    // enforcement lives in RemainingInvoiceService#createDraft/updateDraft/issue's own gates
    // (requireCapacity/requireNotesCapacity) — resolveRemainingInvoice itself, below, never throws
    // for those; it reports them via blockingReason, which createDraft/updateDraft convert into a
    // 409. getRemainingInvoiceOptions above is the live preview this
    // class's callers (and RemainingInvoiceService's own resolveRemainingInvoiceSnapshot)
    // route through.

    /** One resolved snapshot bundle, package-private, reused verbatim by RemainingInvoiceService's
     * createDraft/updateDraft/issue to freeze a STORED remaining
     * invoice off the exact same resolution + customer-header lookup {@link
     * #getRemainingInvoiceOptions} already uses — so the stored document can never compute its
     * content differently than the stateless preview did. {@code ticketSummary} is handed back
     * because both the resolution and the header lookup need it and re-fetching it a second time
     * in the caller would risk reading a different row between the two (a benign race, but an
     * avoidable one). */
    record RemainingInvoiceSnapshot(TicketSummaryDto ticketSummary, CustomerHeaderInfo header,
                                    ResolvedRemainingInvoice resolved, IssueNames names) {}

    /** {@code stored} (null on create) and {@code choice}: เรียน, see {@link #chooseNames}; the header follows it. */
    RemainingInvoiceSnapshot resolveRemainingInvoiceSnapshot(long ticketId, Long quotationId, UserPrincipal actor,
            IssueNames stored, IssueNames.Choice choice) {
        TicketDto ticket = requireTicketViewer(ticketId, actor);
        TicketSummaryDto s = ticket.summary();
        requireQuotationIssuedForRemainingInvoice(s);
        List<CustomerQuotationDto> quotationCandidates = quotations.findByTicket(ticketId);
        List<DepositNoticeDto> notices = docs.findByTicket(ticketId);
        ResolvedRemainingInvoice resolved = resolveRemainingInvoice(
            ticketId, ticket, s, quotationId, quotationCandidates, notices);
        IssueNames names = chooseNames(stored, choice, ticketId, resolved.defaultReference());
        CustomerHeaderInfo header = resolveCustomerHeader(s, null, null, null, ticketId, resolved.defaultReference(),
            names.addresseeCustomerId());
        return new RemainingInvoiceSnapshot(s, header, resolved, names);
    }

    /** Package-private — EXACTLY the predicate {@link #issue} uses to gate issuing a deposit
     * notice (role check + ticket-ownership check), reused verbatim by RemainingInvoiceService for
     * its own create/update/issue/revise/delete gate: "one
     * predicate, not a copied role set" — a caller who can issue a deposit notice on this ticket,
     * and no one else, can also write this ticket's remaining invoice. */
    void requireDepositNoticeIssueGate(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        requireTicketOwner(ticketId, actor);
    }

    private void requireQuotationIssuedForRemainingInvoice(TicketSummaryDto s) {
        if (!"quotation_issued".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ออกใบกำกับภาษีส่วนที่เหลือได้เฉพาะงานที่อยู่ในสถานะ quotation_issued เท่านั้น");
        }
    }

    private String buildRemainingInvoiceDocNumber(long ticketId) {
        // GLR + Thai year (2 digits) + running (sequential per ticket, not a
        // real document sequence; this document is stateless).
        int thaiYear = bangkokToday().getYear() + 543;
        return "GLR" + (thaiYear % 100) + String.format("%03d", ticketId);
    }

    private LocalDate bangkokToday() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Bangkok")));
    }

    /** One resolved bundle: the priced items, the deposit amount/reference defaults, both dialog suggestion lists,
     * the quotation-picker options, and a human blockingReason. */
    // Package-private (not private) — reused verbatim by RemainingInvoiceService
    // to snapshot a STORED remaining invoice off the exact same resolution this stateless preview
    // uses. matchedDepositNoticeId is the deposit_notice_id FK a
    // stored row snapshots for traceability — null when items were quotation-sourced (bypass
    // policy) or there is no accepted quotation at all with nothing to match.
    record ResolvedRemainingInvoice(
        List<RemainingInvoiceItemDto> items,
        BigDecimal depositAmount,
        String defaultReference,
        List<RemainingInvoiceOptionsDto.ReferenceOption> referenceOptions,
        String defaultDepositReference,
        List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions,
        List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions,
        Long defaultQuotationId,
        String blockingReason,
        Long matchedDepositNoticeId,
        // Null = the standard 7% and the sum of the item amounts; set by the new-engine path (EN = 0%, and net =
        // the quotation's stored total_amount).
        BigDecimal vatRate,
        BigDecimal itemsTotalOverride
    ) {
        ResolvedRemainingInvoice(List<RemainingInvoiceItemDto> items, BigDecimal depositAmount, String defaultReference,
            List<RemainingInvoiceOptionsDto.ReferenceOption> referenceOptions, String defaultDepositReference,
            List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions,
            List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions, Long defaultQuotationId,
            String blockingReason, Long matchedDepositNoticeId) {
            this(items, depositAmount, defaultReference, referenceOptions, defaultDepositReference,
                depositReferenceOptions, quotationOptions, defaultQuotationId, blockingReason, matchedDepositNoticeId,
                null, null);
        }

        /** The items total this document bills: the quotation's stored total when set, else the line sum. */
        BigDecimal itemsTotal() {
            return itemsTotalOverride != null ? itemsTotalOverride : sumLines(items);
        }

        private static BigDecimal sumLines(List<RemainingInvoiceItemDto> items) {
            return items.stream().map(RemainingInvoiceItemDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** One quotation this ticket's remaining invoice COULD be sourced from, paired with the deposit notice it
     * matched to — null when the ticket's deposit policy bypasses the deposit notice entirely, meaning this
     * quotation qualifies with no deduction at all. */
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
            // notices is already DESC by version (DepositNoticeRepository#findByTicket) — the first ISSUED hit whose
            // reference matches is the latest matching one.
            DepositNoticeDto matched = notices.stream()
                .filter(n -> "ISSUED".equals(n.status()) && q.number().equals(n.reference()))
                .findFirst().orElse(null);
            if (matched != null) out.add(new QualifyingQuotation(q, matched));
        }
        out.sort(Comparator.comparingLong((QualifyingQuotation qq) -> qq.quotation().id()).reversed());
        return out;
    }

    /** The single entry point both this class's own getRemainingInvoiceOptions and RemainingInvoiceService's
     * create/update/issue (via resolveRemainingInvoiceSnapshot) route through. */
    // Package-private (not private) — RemainingInvoiceService's own resolveRemainingInvoiceSnapshot
    // wrapper below calls this directly.
    ResolvedRemainingInvoice resolveRemainingInvoice(long ticketId, TicketDto ticket, TicketSummaryDto s,
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
                    "ใบเสนอราคาที่เลือกไม่ผ่านเงื่อนไข หรือไม่ใช่ของงานนี้"));
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
        // No deposit to wait for and no legacy quotation: a deal quoted on the NEW engine bills the answered
        // quotation in full. A deal WITH a deposit takes the legacy path below (the issued notice's items).
        if (bypass) {
            DepositNoticeRepository.AnsweredQuotation answered = docs.findAnsweredQuotation(ticketId).orElse(null);
            if (answered != null && answered.newEngine()) {
                return resolveFromNewEngineQuotation(answered, receivedBeforeInvoice(ticketId, answered));
            }
        }
        return resolveLegacy(ticketId, ticket, notices);
    }

    /**
     * The whole answered new-engine quotation, no deposit deduction (bypass policy: there
     * is no notice). It prints the quotation's OWN lines - each line's stored amount, the ส่วนลดพิเศษ row as its own
     * negative line - and money follows the stored total_amount and the document language (EN = no VAT), exactly as
     * payable reads them. A row with no stored total is refused (payable skips it too).
     */
    private ResolvedRemainingInvoice resolveFromNewEngineQuotation(DepositNoticeRepository.AnsweredQuotation answered,
                                                                   BigDecimal deduction) {
        if (answered.totalAmount() == null) {
            return blocked("ใบเสนอราคา " + answered.number() + " ยังไม่มียอดรวมที่บันทึกไว้ จึงออกใบแจ้งหนี้ไม่ได้");
        }
        List<RemainingInvoiceItemDto> items = new ArrayList<>();
        for (DepositNoticeRepository.QuotationInvoiceLine line : docs.findQuotationInvoiceLines(answered.id())) {
            items.add(new RemainingInvoiceItemDto(line.seq(), line.description(), line.qty(), line.unit(),
                line.unitPrice(), line.discountLabel(), line.netUnitPrice(), line.amount()));
        }
        if (items.isEmpty()) {
            return blocked("ใบเสนอราคา " + answered.number() + " ไม่มีรายการสินค้า จึงออกใบแจ้งหนี้ไม่ได้");
        }
        return new ResolvedRemainingInvoice(items, deduction, answered.number(), List.of(), null, List.of(),
            List.of(), null, null, null,
            th.co.glr.hr.dealquotation.WastageCalculator.vatRateFor(answered.documentLanguage()),
            answered.totalAmount());
    }

    /**
     * What a pre-portal deal's remaining invoice deducts: the money received so far (the deposit entered at the start,
     * PRE_PORTAL, and any other receipt), taken back to its pre-VAT part at the invoice's own VAT rate, never above the
     * quotation. Any other deal bills the whole quotation (no deposit was taken), so zero.
     */
    private BigDecimal receivedBeforeInvoice(long ticketId, DepositNoticeRepository.AnsweredQuotation answered) {
        if (!tickets.isPrePortal(ticketId) || answered.totalAmount() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal vat = BigDecimal.ONE.add(th.co.glr.hr.dealquotation.WastageCalculator.vatRateFor(answered.documentLanguage()));
        BigDecimal preVat = tickets.sumPaid(ticketId).divide(vat, 2, RoundingMode.HALF_UP);
        return preVat.max(BigDecimal.ZERO).min(answered.totalAmount());
    }

    /** Deposit deduction (pre-VAT): always the notice's own deposit amount. Money received beyond it (an overpayment)
     * is refunded by account as an ADJUSTMENT receipt, never billed here. */
    private static BigDecimal deductionFor(BigDecimal noticeAmount) {
        return noticeAmount != null ? noticeAmount : BigDecimal.ZERO;
    }

    /** Item/deduction sourcing (rule D11) and rule C (net must not be negative), applied
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
            // An ISSUED notice with a null/empty item snapshot is
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
            // Rule D11: items AND the deduction amount come from the matched
            // deposit notice's own latest ISSUED item snapshot — never the quotation's items. The
            // quotation only identified WHICH notice counts (the reference match in
            // findQualifyingQuotations above); an edited/re-issued notice at a different amount
            // than the quotation IS the agreed amount now, so there is nothing to compare/block on
            // here — there is deliberately no subtotal-must-match rule (matched.subtotal() vs the
            // quotation's own item total).
            items = itemsFromDepositNoticeForRemainingInvoice(matched.items());
            depositAmount = deductionFor(matched.depositAmount());
            DepositRefBundle refs = resolveDepositReferenceOptions(ticketId, matched);
            defaultDepositReference = refs.defaultRef();
            depositReferenceOptions = refs.options();
        } else {
            // Bypass-policy case only (see findQualifyingQuotations/this section's header comment): no deposit notice
            // exists to source from, so items come from the accepted quotation itself — the one case items are still
            // quotation-sourced.
            items = itemsFromQuotationForRemainingInvoice(q.items());
        }
        BigDecimal itemsTotal = sumAmounts(items);

        // Rule C: refuse a negative invoice. Net == 0 is allowed (compareTo < 0 only).
        if (itemsTotal.subtract(depositAmount).compareTo(BigDecimal.ZERO) < 0) {
            return new ResolvedRemainingInvoice(items, depositAmount, q.number(),
                buildQuotationReferenceOptions(quotationCandidates).referenceOptions(), null, List.of(),
                quotationOptions, defaultQuotationId,
                "ยอดหลังหักมัดจำติดลบ (ยอดสินค้า " + fmt(itemsTotal) + " น้อยกว่ายอดมัดจำ " + fmt(depositAmount)
                    + " บาท) กรุณาตรวจสอบใบแจ้งยอดมัดจำหรือใบเสนอราคา " + q.number(),
                matched != null ? matched.id() : null);
        }

        return new ResolvedRemainingInvoice(items, depositAmount, q.number(),
            buildQuotationReferenceOptions(quotationCandidates).referenceOptions(),
            defaultDepositReference, depositReferenceOptions, quotationOptions, defaultQuotationId, null,
            matched != null ? matched.id() : null);
    }

    /** Pre-PCR-chain back-compat only — reached exclusively when the ticket has NO accepted
     * CustomerQuotation at all (see resolveRemainingInvoice's own Javadoc). Sources items,
     * reference and deposit reference as before the quotation-selection rules existed: the
     * latest ISSUED deposit notice's own item snapshot, else the
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
                    "ไม่พบใบเสนอราคาที่ลูกค้ายอมรับ หรือใบแจ้งยอดมัดจำที่ออกแล้วสำหรับงานนี้ จึงไม่สามารถออกใบแจ้งหนี้ส่วนที่เหลือได้");
            }
        }
        BigDecimal depositAmount = latestIssued == null ? BigDecimal.ZERO
            : deductionFor(latestIssued.depositAmount());
        BigDecimal itemsTotal = sumAmounts(items);
        if (itemsTotal.subtract(depositAmount).compareTo(BigDecimal.ZERO) < 0) {
            return new ResolvedRemainingInvoice(items, depositAmount, legacyReference(latestIssued), List.of(), null, List.of(),
                List.of(), null,
                "ยอดหลังหักมัดจำติดลบ (ยอดสินค้า " + fmt(itemsTotal) + " น้อยกว่ายอดมัดจำ " + fmt(depositAmount) + " บาท)",
                latestIssued != null ? latestIssued.id() : null);
        }
        String defaultDepositReference = null;
        List<RemainingInvoiceOptionsDto.ReferenceOption> depositReferenceOptions = List.of();
        if (latestIssued != null) {
            DepositRefBundle refs = resolveDepositReferenceOptions(ticketId, latestIssued);
            defaultDepositReference = refs.defaultRef();
            depositReferenceOptions = refs.options();
        }
        return new ResolvedRemainingInvoice(items, depositAmount, legacyReference(latestIssued), List.of(),
            defaultDepositReference, depositReferenceOptions, List.of(), null, null,
            latestIssued != null ? latestIssued.id() : null);
    }

    /** Legacy path (no accepted quotation): the reference defaults to the latest ISSUED deposit notice's own. */
    private String legacyReference(DepositNoticeDto latestIssued) {
        return latestIssued == null ? null : blankToNull(latestIssued.reference());
    }

    private ResolvedRemainingInvoice blocked(String reason) {
        return new ResolvedRemainingInvoice(List.of(), BigDecimal.ZERO, null, List.of(), null, List.of(),
            List.of(), null, reason, null);
    }

    /** Same as {@link #blocked}, but also carries the quotation-picker options through — used when
     * the block happens INSIDE {@link #resolveFromQuotation} (a specific quotation was already
     * chosen), so the dialog can still offer switching to a different qualifying quotation instead
     * of losing the picker entirely. Example: an ISSUED notice matched with
     * a null/empty item snapshot. */
    private ResolvedRemainingInvoice blockedWithOptions(String reason,
            List<RemainingInvoiceOptionsDto.QuotationOption> quotationOptions, Long defaultQuotationId) {
        return new ResolvedRemainingInvoice(List.of(), BigDecimal.ZERO, null, List.of(), null, List.of(),
            quotationOptions, defaultQuotationId, reason, null);
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

    /** H9 suggestion list: ACCEPTED quotations first, then ISSUED — a free-text datalist, so this stays the FULL
     * candidate list regardless of which one the deposit deduction actually came from. */
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

    /** Rule D: deposit-reference dropdown = receipt_refs of DEPOSIT payment_receipt rows LINKED to the matched
     * notice (deposit_notice_id) — falling back to ALL DEPOSIT receipts on the ticket only when none are linked —
     * plus the matched notice's own number, plus a blank option. */
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
     * {@link DepositNoticeRepository#findGoverningQuotation} does for the deposit notice. */
    private CustomerQuotationDto pickAcceptedQuotation(List<CustomerQuotationDto> candidates) {
        CustomerQuotationDto latestAccepted = null;
        for (CustomerQuotationDto q : candidates) {
            if (QuotationStatus.ACCEPTED.equals(q.docStatus())) latestAccepted = q;
        }
        return latestAccepted;
    }

    /** Preview/render agreement: the ONE shared line-amount function for a quotation
     * item, used both by the subtotal-match check above and by
     * {@link #itemsFromQuotationForRemainingInvoice}'s own {@code amount} field below —
     * {@code line_subtotal} is nullable (V74), so a null there must never silently diverge between
     * the options preview and the actual renderer input. Stored {@code lineSubtotal} wins when
     * present (the customer's own agreed figure — see quotation-arithmetic-reconciled: pieces
     * round UP, so it can differ from a naive recompute by a piece); otherwise
     * HALF_UP(finalUnitPrice × qty). */
    private BigDecimal quotationLineAmount(CustomerQuotationItemDto item) {
        if (item.lineSubtotal() != null) return item.lineSubtotal();
        BigDecimal price = item.finalUnitPrice() != null ? item.finalUnitPrice() : BigDecimal.ZERO;
        BigDecimal qty = item.requestedQuantity() != null ? item.requestedQuantity() : BigDecimal.ZERO;
        return price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** ส่วนลด cell text for a legacy CustomerQuotation line, which stores a per-unit discount AMOUNT, not a percent:
     * the percent of the approved price ("N%"), or "Net" with no discount - the same texts the quotation prints. */
    static String legacyDiscountLabel(BigDecimal approvedUnitPrice, BigDecimal discountPerUnit) {
        BigDecimal pct = discountPerUnit != null && discountPerUnit.signum() > 0 && approvedUnitPrice != null
            && approvedUnitPrice.signum() > 0
            ? discountPerUnit.multiply(BigDecimal.valueOf(100)).divide(approvedUnitPrice, 2, RoundingMode.HALF_UP) : null;
        return th.co.glr.hr.dealquotation.DealQuotationLines.discountCellText(
            null, th.co.glr.hr.dealquotation.WastageCalculator.PRICE_MODE_NET, pct, null, null, false);
    }

    private List<RemainingInvoiceItemDto> itemsFromQuotationForRemainingInvoice(List<CustomerQuotationItemDto> quotationItems) {
        List<RemainingInvoiceItemDto> out = new ArrayList<>();
        for (CustomerQuotationItemDto item : quotationItems) {
            String description = item.description() != null && !item.description().isBlank()
                ? item.description() : "รายการสินค้า";
            out.add(new RemainingInvoiceItemDto(
                item.seq(), description, item.requestedQuantity(), unitLabel(item.requestedUnitBasis()),
                item.approvedUnitPrice(), legacyDiscountLabel(item.approvedUnitPrice(), item.salesDiscount()), item.finalUnitPrice(),
                // The customer's own accepted subtotal — never recomputed as finalUnitPrice*qty outright (see
                // quotationLineAmount's own Javadoc for the null-handling rule).
                quotationLineAmount(item)));
        }
        return out;
    }

    private List<RemainingInvoiceItemDto> itemsFromDepositNoticeForRemainingInvoice(List<DepositNoticeItemDto> noticeItems) {
        List<RemainingInvoiceItemDto> out = new ArrayList<>();
        for (DepositNoticeItemDto item : noticeItems) {
            // The notice's stored label is the quotation's own ส่วนลด cell text: carried through as-is.
            out.add(new RemainingInvoiceItemDto(
                item.seq(), item.description(), item.qty(), item.unit(),
                item.unitPrice(), item.discountLabel(), item.netUnitPrice(), item.amount()));
        }
        return out;
    }

    /** Legacy back-compat path — same shape getRemainingInvoiceXlsx always used before this branch, kept for any
     * ticket manually approved before the pricing-request chain existed (approved_price set directly on
     * ticket_item, no quotation ever created). */
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

    private String nullSafe(String s) { return s != null ? s : ""; }

    // Revision flow (Part A of plan)
    @Transactional
    public TicketDto requestRevision(long ticketId, RevisionRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        TicketSummaryDto s = ticket.summary();
        String st = s.status();
        requireActiveLifecycle(s);

        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "ขอแก้ไข revision ได้เฉพาะจากสถานะ approved หรือ document_issued เท่านั้น");
        }
        if (!ownsDeal(s, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะเจ้าของงานเท่านั้นที่สามารถขอแก้ไขใบเสนอราคาได้");
        }

        String toStatus = switch (req.scope()) {
            case QTY_OR_NOTE   -> TicketStatus.APPROVED;          // stays approved, new document version
            case PRICE_CHANGE  -> TicketStatus.PRICE_PROPOSED;    // CEO re-approves
            case NEW_ITEM      -> TicketStatus.IN_REVIEW;         // Import re-prices
        };

        // The status move is an EXPLICIT transition, not a side effect of logging the
        // event below: TicketRepository.addEvent does not touch the status column, so without
        // this call the revision would log its event and leave the deal sitting where it was.
        //
        // Two of these three edges deliberately move the deal BACKWARDS (approved/document_issued
        // -> price_proposed for the CEO to re-approve, -> in_review for Import to re-price) and one
        // is the approved -> approved self-edge; all six from/to pairs reachable from the guard
        // above are declared in TicketStatus.ALLOWED as intentional.
        int rows = tickets.transitionStatus(ticketId, st, toStatus);
        if (rows == 0) {
            // Lost compare-and-set: another writer moved sales.ticket.status between the read above and this write. A
            // conflict, never a re-SELECT for a nicer message — see TicketRepository.transitionStatus's own Javadoc.
            throw new ApiException(HttpStatus.CONFLICT,
                "สถานะงานถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
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

    /** Renders the issued document once the transaction that issued it has COMMITTED — or immediately when no
     * transaction is active. See #issue's own comment for why the render must not run inside the transaction. */
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
            DepositNoticeRenderModel model = renderModel(issued, bangkokToday());
            renderer.toPdf(model);
            renderer.toXls(model);
            docs.setFilePaths(docId, "rendered", "rendered");
        } catch (Exception e) {
            // Non-fatal: files can be regenerated on download.
        }
    }

    private TicketSummaryDto requireApprovedTicket(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        String st = t.summary().status();
        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.QUOTATION_ISSUED.equals(st)
                && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "สร้างใบแจ้งรับมัดจำได้เฉพาะงานที่อนุมัติแล้วเท่านั้น");
        }
        return t.summary();
    }

    /**
     * Lifecycle gate (mirrors TicketService.requireActive): deposit-notice
     * mutations advance the payment track, so a paused/terminal deal blocks them.
     */
    // Package-private (not private) — reused as-is by RemainingInvoiceService.
    void requireActiveLifecycle(TicketSummaryDto summary) {
        if (!DealLifecycle.ACTIVE.equals(summary.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "งานไม่ได้อยู่ในสถานะ ACTIVE (" + summary.lifecycle() + ") จึงแก้ไขขั้นตอนนี้ไม่ได้");
        }
    }

    /** DRAFT or ISSUED (an issued notice is edited in place); a SUPERSEDED one is read-only (409). */
    private DepositNoticeDto requireEditable(long docId) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบแจ้งรับมัดจำนี้"));
        if (!"DRAFT".equals(doc.status()) && !"ISSUED".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, DepositNoticeRepository.SUPERSEDED);
        }
        return doc;
    }

    private DepositNoticeDto requireDraft(long docId) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบแจ้งรับมัดจำนี้"));
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, DepositNoticeRepository.NOT_DRAFT);
        }
        return doc;
    }

    /**
     * Read gate mirroring TicketService.requireViewAccess: viewer role required,
     * sales reps only for their own tickets.
     *
     * <p>Role-scoped views: import is denied outright, unlike
     * TicketService.requireViewAccess (which strips only the embedded quotation but
     * still lets import view the rest of the ticket). A deposit notice IS a customer
     * financial document end to end — there is no "rest of it" for import to see —
     * so every read here (list/get/preview/download/remaining-invoice) is off-limits,
     * matching salesViewScope.js hiding the whole "depositNotice" section from import.
     */
    // Package-private (not private) — "the existing requireTicketViewer" RemainingInvoiceService's
    // read/list/download gate reuses directly.
    TicketDto requireTicketViewer(long ticketId, UserPrincipal actor) {
        if (!readsByGrant(actor)) {
            requireRole(actor, VIEWER_ROLES);
        }
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        // The per-role half is dealDocumentScope (below), which the document chain's list mark applies in SQL too.
        switch (readScope(actor)) {
            case ALL -> { }
            case OWNER_OR_KEYER -> {
                if (!ownsDeal(t.summary(), actor)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
                }
            }
            // account is ROW-scoped -- it reads a deal's deposit notices / remaining invoices only
            // when the deal is inside its list scope (the same predicate that filters GET /api/tickets).
            // Every deposit-notice and remaining-invoice read funnels through this method.
            // Read gate only (deposit-notice writes use requireTicketOwner): also a lost/cancelled deal that still has an
            // issued deposit notice, so the finance page can show its documents. This method is
            // also reached on remaining-invoice writes via resolveRemainingInvoiceSnapshot; those are blocked for a dead
            // deal by requireDepositNoticeIssueGate + requireActiveLifecycle, so the widening never lets one through.
            case ACCOUNT -> {
                if (!tickets.isInAccountScope(ticketId) && !tickets.isDeadDealWithIssuedDepositNotice(ticketId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
                }
            }
            case NONE -> throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return t;
    }

    /** Which deals' documents (deposit notices, remaining invoices) a role may read — the per-role half of
     * {@link #requireTicketViewer}, which applies it to one deal. */
    public enum DealDocumentScope {
        /** every deal: ceo, sales_manager (read-only oversight) */
        ALL,
        /** deals the viewer owns or keyed: sales */
        OWNER_OR_KEYER,
        /** deals inside account's list scope (plus a dead deal with an issued notice): account */
        ACCOUNT,
        /** none: import (a deposit notice is a customer financial document end to end), and every non-viewer role */
        NONE
    }

    public static DealDocumentScope dealDocumentScope(String role) {
        if (role == null || !VIEWER_ROLES.contains(role) || IMPORT_ROLES.contains(role)) {
            return DealDocumentScope.NONE;
        }
        return switch (role) {
            case "sales" -> DealDocumentScope.OWNER_OR_KEYER;
            case "account" -> DealDocumentScope.ACCOUNT;
            default -> DealDocumentScope.ALL;
        };
    }

    /** The read-all grant holder (never import/account; sales_manager already reads by role). READ gates only: it is
     * not in {@link #mayWriteDealDocuments} or {@link #ownsDeal}, so a holder never writes a document. */
    public boolean readsByGrant(UserPrincipal actor) {
        return th.co.glr.hr.auth.OversightReader.holder(actor, tickets::holdsReadAllSalesGrant);
    }

    /** True when {@code actor} reads this deal only through the read-all grant (no DRAFT delivery-round files for them). */
    private boolean readsOnlyByGrant(long ticketId, UserPrincipal actor) {
        return readsByGrant(actor) && !"sales_manager".equals(actor.role()) && !"ceo".equals(actor.role())
            && tickets.findById(ticketId).map(t -> !ownsDeal(t.summary(), actor)).orElse(false);
    }

    /** The per-deal read scope of {@code actor}: the all-documents grant or the read-all grant reads every deal. */
    public DealDocumentScope readScope(UserPrincipal actor) {
        return tickets.holdsAllSalesDocumentsGrant(actor) || readsByGrant(actor)
            ? DealDocumentScope.ALL : dealDocumentScope(actor.role());
    }

    /** {@link #requireTicketViewer} for callers outside this package (the document chain summary). */
    public void requireDealDocumentViewer(long ticketId, UserPrincipal actor) {
        requireTicketViewer(ticketId, actor);
    }

    /** The identity half of every deposit-notice and remaining-invoice WRITE: {@link #requireDepositNoticeIssueGate}
     * (and createDraft/update/issue's own requireRole(SALES_ROLES) + requireTicketOwner) as a yes/no, for the document
     * chain's "may this viewer act" flags. */
    public boolean mayWriteDealDocuments(TicketSummaryDto summary, UserPrincipal actor) {
        return (SALES_ROLES.contains(actor.role()) && DealOwnership.isOwnerOrKeyer(summary, actor.id()))
            || tickets.holdsAllSalesDocumentsGrant(actor);
    }

    /** Owner/keyer of the deal, or a live {@code can_manage_all_sales_documents} holder (acts as owner of every deal). */
    private boolean ownsDeal(TicketSummaryDto s, UserPrincipal actor) {
        return DealOwnership.isOwnerOrKeyer(s, actor.id()) || tickets.holdsAllSalesDocumentsGrant(actor);
    }

    private TicketSummaryDto requireTicketOwner(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        if (!ownsDeal(t.summary(), actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return t.summary();
    }

    private void requireRole(UserPrincipal actor, java.util.Set<String> allowed) {
        // a can_manage_all_sales_documents holder (sales / sales_manager only, see the grant read) acts as the sales owner
        if (!allowed.contains(actor.role()) && !(allowed.contains("sales") && tickets.holdsAllSalesDocumentsGrant(actor))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** 0.40 is a whole 40%; 0.405 and 0.3330 are not. */
    private static boolean isWholePercent(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().scale() <= 0;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Resolved customer-master header fields, shared by createDraft (deposit notice) and the
     * remaining-invoice methods below — see {@link #resolveCustomerHeader} for the lookup rule.
     * {@code branch} is carried standalone: createDraft only folds branch INTO the joined address
     * string, but the remaining invoice's B7 line needs it
     * separately ("<name> (<branch>) / เลขประจำตัวผู้เสียภาษี : <taxId>"). {@code addressOnly} is
     * the address WITHOUT the branch folded in, because the remaining invoice's B8 must not repeat
     * the branch (B7 already shows it in parens) — {@code address} itself is what createDraft
     * builds (address+branch joined) and must not change. */
    // Package-private (not private) — reused by RemainingInvoiceService's own customer snapshot.
    record CustomerHeaderInfo(String taxId, String address, String addressOnly, String branch, String projectName,
                              String customerName) {
        /** The quotation party's customer name when the header came from one, else {@code fallback} (the deal's). */
        String customerNameOr(String fallback) {
            return customerName != null ? customerName : fallback;
        }
    }

    /**
     * Single source of truth for "where does a document's customer header come from", used by
     * createDraft (deposit notice), getRemainingInvoiceOptions (remaining invoice preview), and
     * RemainingInvoiceService's own stored snapshot (remaining invoice, via
     * resolveRemainingInvoiceSnapshot) — shared so no remaining-invoice document can ever source a
     * customer's address/tax id/branch
     * differently. A caller-supplied
     * non-blank {@code reqTaxId}/{@code reqAddress}/{@code reqProjectName} always wins (createDraft
     * is the only caller that ever passes one — the remaining-invoice methods pass all-null,
     * meaning "always resolve from the customer master"); a null {@code customerId} (never linked
     * to a customer master row) safely leaves these fields blank rather than throwing.
     */
    // Package-private (not private) — see resolveRemainingInvoiceSnapshot above.
    CustomerHeaderInfo resolveCustomerHeader(TicketSummaryDto s,
            String reqTaxId, String reqAddress, String reqProjectName) {
        return resolveCustomerHeader(s, reqTaxId, reqAddress, reqProjectName, s.id(), null);
    }

    /**
     * เรียน of a document of this deal (feedback #74, Ploy: "select the same customer for quotation (default) or create an
     * entirely new customer"). Starts from {@code stored}, or on create from the default: the เรียน already chosen on this
     * deal's documents for quotation {@code quotationNumber}, else that quotation's customer (rule #65). Then applies
     * {@code c}: a picked customer-list row (400 when unknown; the quotation's own customer is no override) or a reset.
     * Shared by the deposit notice and the remaining invoice.
     */
    IssueNames chooseNames(IssueNames stored, IssueNames.Choice c, long ticketId, String quotationNumber) {
        IssueNames names = stored != null ? stored
            : new IssueNames(quotationNumber == null ? null : docs.findChosenAddressee(ticketId, quotationNumber).orElse(null));
        if (c.addresseeCustomerId() != null) {
            requireListedCustomer(c.addresseeCustomerId());
            Long quotationCustomer = quotationNumber == null ? null
                : docs.findQuotationPartyCustomerId(ticketId, quotationNumber).orElse(null);
            names = new IssueNames(c.addresseeCustomerId().equals(quotationCustomer) ? null : c.addresseeCustomerId());
        } else if (Boolean.TRUE.equals(c.addresseeReset())) {
            names = IssueNames.NONE;
        }
        return names;
    }

    private void requireListedCustomer(long customerId) {
        if (customers.findById(customerId).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบลูกค้าที่เลือก");
        }
    }

    /** A document a live billing note claims is billed under its เรียน customer (BillingNoteRepository#candidatesForCustomer),
     * so เรียน may not change until that billing note is cancelled or revised without it. */
    void requireNamesUnbilled(String sourceType, long sourceId, IssueNames before, IssueNames after, String documentLabel) {
        if (java.util.Objects.equals(before, after)) return;
        docs.findLiveBillingNoteClaim(sourceType, sourceId).ifPresent(claim -> {
            throw new ApiException(HttpStatus.CONFLICT, documentLabel + "นี้อยู่ในใบวางบิล "
                + (claim.docNumber() == null ? "ฉบับร่าง" : claim.docNumber())
                + " แล้ว จึงเปลี่ยนลูกค้าที่เรียนไม่ได้ ให้ยกเลิกใบวางบิลหรือนำเอกสารนี้ออกจากใบวางบิลก่อน");
        });
    }

    /** The buyer printed on anything tied to a quotation: the เรียน chosen on the deal's documents for it (feedback #74), else
     * that quotation's party customer (owner rule #65), else the deal's own customer ({@code fromDeal}). Read-only; delivery rounds show it to sales, the warehouse and the issuer. */
    public record QuotationBuyer(String name, String taxId, String branch, boolean fromDeal) {}

    public QuotationBuyer quotationBuyer(TicketSummaryDto s, String quotationNumber) {
        // the เรียน chosen on the deal's documents for this quotation (feedback #74: the tax invoice goes in that name too)
        Long chosen = quotationNumber == null ? null : docs.findChosenAddressee(s.id(), quotationNumber).orElse(null);
        CustomerHeaderInfo h = resolveCustomerHeader(s, null, null, null, s.id(), quotationNumber, chosen);
        return new QuotationBuyer(h.customerNameOr(s.customerName()), h.taxId(), h.branch(), h.customerName() == null);
    }

    /** As above, but when {@code quotationNumber} names a new-engine quotation addressed to a party whose customer is
     * (any BUYER party, headline or not), the name / tax id / branch / address come ONLY from THAT customer: typed values
     * are ignored. Anything else (no number, a legacy number, no party) behaves exactly as the 4-argument form. */
    CustomerHeaderInfo resolveCustomerHeader(TicketSummaryDto s,
            String reqTaxId, String reqAddress, String reqProjectName, long ticketId, String quotationNumber) {
        return resolveCustomerHeader(s, reqTaxId, reqAddress, reqProjectName, ticketId, quotationNumber, null);
    }

    /** As above; a non-null {@code addresseeCustomerId} (เรียน picked from the customer list, feedback #74) takes the
     * quotation party customer's place. */
    CustomerHeaderInfo resolveCustomerHeader(TicketSummaryDto s, String reqTaxId, String reqAddress,
            String reqProjectName, long ticketId, String quotationNumber, Long addresseeCustomerId) {
        Long customerId = s.customerId();
        String partyCustomerName = null;
        String customerTaxId = blankToNull(reqTaxId);
        String customerAddress = blankToNull(reqAddress);
        if (addresseeCustomerId != null || (quotationNumber != null && !quotationNumber.isBlank())) {
            Long partyCustomerId = addresseeCustomerId != null ? addresseeCustomerId
                : docs.findQuotationPartyCustomerId(ticketId, quotationNumber).orElse(null);
            CustomerDto party = partyCustomerId == null ? null : customers.findById(partyCustomerId).orElse(null);
            if (party != null) {
                // the quotation's customer is the only source: typed values do not win
                customerId = partyCustomerId;
                partyCustomerName = blankToNull(party.name());
                customerTaxId = null;
                customerAddress = null;
            }
        }
        String addressOnly = null;
        String projectName = blankToNull(reqProjectName);
        String branch = null;
        if ((customerTaxId == null || customerAddress == null) && customerId != null) {
            CustomerDto customer = customers.findById(customerId).orElse(null);
            if (customer != null) {
                addressOnly = blankToNull(customer.address());
                if (customerTaxId == null) {
                    customerTaxId = blankToNull(customer.taxId());
                }
                branch = th.co.glr.hr.customer.CustomerPrintRule.branch(customer.branch(), customerTaxId);
                if (customerAddress == null) {
                    customerAddress = th.co.glr.hr.customer.CustomerPrintRule.addressWithBranch(
                        customer.address(), customer.branch(), customerTaxId);
                }
            }
        }
        if (addressOnly == null) {
            addressOnly = customerAddress;
        }
        if (projectName == null) {
            projectName = blankToNull(s.projectName());
        }
        return new CustomerHeaderInfo(customerTaxId, customerAddress, addressOnly, branch, projectName, partyCustomerName);
    }

    /** A client line may only echo what the notice stores: its amount counts only when a stored line has the same
     * qty, net and amount (else it is recomputed), and a negative qty may be kept but never added (the lines
     * supplied with a given negative qty may not outnumber the stored lines with that qty). */
    private static List<DepositNoticeItemRequest> echoOnly(List<DepositNoticeItemRequest> supplied,
                                                           List<DepositNoticeItemDto> stored) {
        if (supplied == null) return null;
        return supplied.stream().map(it -> {
            if (it.qty().signum() < 0 && supplied.stream().filter(o -> o.qty().compareTo(it.qty()) == 0).count()
                    > stored.stream().filter(s -> s.qty().compareTo(it.qty()) == 0).count()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนต้องไม่ติดลบ");
            }
            boolean echoed = it.amount() != null && stored.stream().anyMatch(s ->
                s.qty().compareTo(it.qty()) == 0 && s.netUnitPrice().compareTo(it.netUnitPrice()) == 0
                    && s.amount().compareTo(it.amount()) == 0);
            return echoed || it.amount() == null ? it : new DepositNoticeItemRequest(it.seq(), it.description(),
                it.qty(), it.unit(), it.unitPrice(), it.discountLabel(), it.netUnitPrice(), null);
        }).toList();
    }

    private List<DepositNoticeItemRequest> buildItemsFromRequest(DepositNoticeDraftRequest req, long ticketId) {
        if (req.items() != null && !req.items().isEmpty()) return req.items();

        // Legacy path: sales.ticket_item.approved_price, written only by the @Deprecated TicketService.approve (no
        // controller route) — kept working for any ticket that still carries it, e.g. one manually approved before the
        // pricing-request chain existed.
        return buildLegacyItems(ticketId);
    }

    /** Every deal created through the pricing-request chain has approved_price = NULL on every
     * ticket_item, so its lines come from the governing quotation, whichever engine it is. */
    private List<DepositNoticeItemRequest> itemsFromGoverning(DepositNoticeRepository.GoverningQuotation governing) {
        if (!governing.legacy()) return docs.findDealQuotationItems(governing.quotationId());
        return quotations.findById(governing.quotationId())
            .map(q -> itemsFromQuotation(q.items()))
            .orElse(List.of());
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
                            seq[0]++, desc, it.qty(), "แผ่น", price, null, price, null
                        );
                    })
                    .toList();
            })
            .orElse(List.of());
    }

    /** The pricing-request route's new-engine item source, so the draft's rows come from the
     * quotation its reference names. */
    public List<DepositNoticeItemRequest> itemsFromDealQuotation(long quotationId) {
        return docs.findDealQuotationItems(quotationId);
    }

    /**
     * Maps a customer quotation's items to deposit-notice item requests. The single shared mapping
     * used by both {@link #itemsFromGoverning} (a legacy governing quotation's lines, above)
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
            String discountLabel = legacyDiscountLabel(item.approvedUnitPrice(), item.salesDiscount());
            items.add(new DepositNoticeItemRequest(
                item.seq(), description, item.requestedQuantity(), unitLabel(item.requestedUnitBasis()),
                item.approvedUnitPrice(), discountLabel, item.finalUnitPrice(), null));
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
