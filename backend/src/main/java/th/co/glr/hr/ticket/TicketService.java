package th.co.glr.hr.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;

@Service
public class TicketService {
    private static final Set<String> SALES_ROLES  = Set.of("sales");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> CEO_ROLES    = Set.of("ceo");
    private static final Set<String> QUOTATION_ALLOWED_STATUSES =
        Set.of(TicketStatus.APPROVED, TicketStatus.QUOTATION_ISSUED);
    private static final Set<String> PROPOSE_ALLOWED_STATUSES =
        Set.of(TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED);

    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final PriceCalcService priceCalcService;
    private final ObjectMapper objectMapper;
    private final CustomerRepository customers;
    private final QuotationRenderer quotationRenderer;

    public TicketService(TicketRepository tickets, NotificationRepository notifications,
                         PriceCalcService priceCalcService, ObjectMapper objectMapper,
                         CustomerRepository customers, QuotationRenderer quotationRenderer) {
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.priceCalcService  = priceCalcService;
        this.objectMapper      = objectMapper;
        this.customers         = customers;
        this.quotationRenderer = quotationRenderer;
    }

    public List<TicketSummaryDto> list(String status, UserPrincipal actor) {
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return tickets.findSummaries(status, createdByFilter);
    }

    public Page<TicketSummaryDto> listPage(String status, UserPrincipal actor, PageRequest page) {
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        List<TicketSummaryDto> rows = tickets.findSummaries(status, createdByFilter, page);
        // Skip the COUNT round-trip when the whole result set fits on page 0.
        int total = (page.page() == 0 && rows.size() < page.size())
            ? rows.size()
            : tickets.countSummaries(status, createdByFilter);
        return new Page<>(rows, page.page(), page.size(), total);
    }

    public TicketDto get(long id, UserPrincipal actor) {
        TicketDto ticket = requireTicket(id);
        if ("sales".equals(actor.role()) && ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return ticket;
    }

    @Transactional
    public TicketDto create(CreateTicketRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        String code = tickets.nextTicketCode();
        long id = tickets.create(request, code, actor.id(), actor.name());
        notifications.notifyByRole("import", id, "SUBMITTED",
            "Ticket " + code + " รอการรับเรื่อง");
        notifications.notifyByRole("ceo", id, "SUBMITTED",
            "Ticket " + code + " ส่งเข้าระบบแล้ว");
        return requireTicket(id);
    }

    @Transactional
    public TicketDto submit(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.DRAFT);
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can submit");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.SUBMITTED, TicketStatus.DRAFT, TicketStatus.SUBMITTED, null);
        notifications.notifyByRole("import", ticketId, "SUBMITTED",
            "Ticket " + s.code() + " รอการรับเรื่อง");
        notifications.notifyByRole("ceo", ticketId, "SUBMITTED",
            "Ticket " + s.code() + " ส่งเข้าระบบแล้ว");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto pickup(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        loadAndVerifyStatus(ticketId, TicketStatus.SUBMITTED);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.PICKED_UP, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto proposePrice(long ticketId, ProposePriceRequest request, UserPrincipal actor) {
        if (!IMPORT_ROLES.contains(actor.role()) && !isAdmin(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        String currentStatus = s.status();
        if (!PROPOSE_ALLOWED_STATUSES.contains(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot propose price when ticket is '" + currentStatus + "'");
        }
        tickets.replaceItems(ticketId, request.items());
        String snapshot = buildItemSnapshot(request.items());
        boolean isRevision = !TicketStatus.IN_REVIEW.equals(currentStatus);
        String eventKind = isRevision ? TicketEventKind.PRICE_REVISED : TicketEventKind.PRICE_PROPOSED;
        tickets.addEventWithSnapshot(ticketId, actor.id(), actor.name(),
            eventKind, currentStatus, TicketStatus.PRICE_PROPOSED, request.note(), snapshot);
        notifications.notifyByRole("ceo", ticketId, "PRICE_PROPOSED",
            "Ticket " + s.code() + (isRevision ? " มีการแก้ไขราคาเสนอ — กรุณาตรวจสอบใหม่" : " มีราคาเสนอรอการอนุมัติ"));
        return requireTicket(ticketId);
    }

    private String buildItemSnapshot(List<TicketItemRequest> items) {
        try {
            record ItemSnap(String brand, String model, BigDecimal qty,
                            BigDecimal rawPrice, String rawCurrency, String rawUnit) {}
            var snaps = items.stream()
                .map(it -> new ItemSnap(it.brand(), it.model(), it.qty(),
                                        it.rawPrice(), it.rawCurrency(), it.rawUnit()))
                .toList();
            return objectMapper.writeValueAsString(snaps);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public TicketDto approve(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        tickets.approveItemPrices(ticketId);
        tickets.setHasEdits(ticketId, false);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.APPROVED, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED, null);
        notifications.notifyEmployee(s.createdById(), ticketId, "APPROVED",
            "Ticket " + s.code() + " ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto reject(long ticketId, RejectRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REJECTED, TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW, request.reason());
        notifications.notifyByRole("import", ticketId, "REJECTED",
            "Ticket " + s.code() + " ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto generateQuotation(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        String fromStatus = s.status();
        if (!QUOTATION_ALLOWED_STATUSES.contains(fromStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'approved' or 'quotation_issued' but ticket is '" + fromStatus + "'");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can generate a quotation");
        }
        TicketDto full = requireTicket(ticketId);
        BigDecimal total = full.items().stream()
            .map(item -> {
                BigDecimal price = item.approvedPrice() != null ? item.approvedPrice() : BigDecimal.ZERO;
                return price.multiply(item.qty());
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = tickets.nextQuotationCode();
        tickets.createQuotation(ticketId, number, actor.id(), total);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.QUOTATION_ISSUED, fromStatus, TicketStatus.QUOTATION_ISSUED, null);
        return requireTicket(ticketId);
    }

    // Renders the quotation straight from the current ticket + quotation record — there is no
    // separate draft/edit phase, so "regenerating" just means re-rendering from live data.
    public byte[] getQuotationXlsx(long ticketId, long quotationId, UserPrincipal actor) {
        var ctx = loadQuotationContext(ticketId, quotationId, actor);
        return quotationRenderer.toXlsx(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    public byte[] getQuotationPdf(long ticketId, long quotationId, UserPrincipal actor) {
        var ctx = loadQuotationContext(ticketId, quotationId, actor);
        return quotationRenderer.toPdf(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    private record QuotationRenderContext(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {}

    private QuotationRenderContext loadQuotationContext(long ticketId, long quotationId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        if ("sales".equals(actor.role()) && ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        QuotationDto quotation = ticket.quotations().stream()
            .filter(q -> q.id() == quotationId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quotation not found"));
        CustomerDto customer = ticket.summary().customerId() != null
            ? customers.findById(ticket.summary().customerId()).orElse(null)
            : null;
        return new QuotationRenderContext(ticket, quotation, customer);
    }

    @Transactional
    public TicketDto close(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        if (s.createdById() != actor.id() && !isAdmin(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String st = s.status();
        // Legacy path: status=DOCUMENT_ISSUED
        // Dual-track path: both tracks complete
        boolean legacyOk = TicketStatus.DOCUMENT_ISSUED.equals(st);
        boolean dualTrackOk = TicketStatus.QUOTATION_ISSUED.equals(st)
            && "FULLY_PAID".equals(s.paymentStatus())
            && "GOODS_RECEIVED".equals(s.fulfillmentStatus());
        if (!legacyOk && !dualTrackOk) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot close: require paymentStatus=FULLY_PAID and fulfillmentStatus=GOODS_RECEIVED");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSED, st, TicketStatus.CLOSED, null);
        return requireTicket(ticketId);
    }

    // ── Dual-track transitions (ข้อ 13) ─────────────────────────────────────

    @Transactional
    public TicketDto confirmCustomer(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        tickets.updatePaymentStatus(ticketId, "CUSTOMER_CONFIRMED");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CUSTOMER_CONFIRMED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto issueDepositNotice(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status())
                || !"CUSTOMER_CONFIRMED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Deposit notice requires quotation_issued + paymentStatus=CUSTOMER_CONFIRMED");
        }
        tickets.updatePaymentStatus(ticketId, "DEPOSIT_NOTICE_ISSUED");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.DEPOSIT_NOTICE_ISSUED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto confirmDepositPaid(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected paymentStatus=DEPOSIT_NOTICE_ISSUED");
        }
        tickets.updatePaymentStatus(ticketId, "DEPOSIT_PAID");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.DEPOSIT_PAID, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto issueImportRequest(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status())
                || !"DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "IR requires quotation_issued + paymentStatus=DEPOSIT_NOTICE_ISSUED");
        }
        tickets.updateFulfillmentStatus(ticketId, "IR_ISSUED");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_ISSUED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markIrSent(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"IR_ISSUED".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_ISSUED");
        }
        tickets.updateFulfillmentStatus(ticketId, "IR_SENT");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_SENT, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markShipping(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"IR_SENT".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_SENT");
        }
        tickets.updateFulfillmentStatus(ticketId, "SHIPPING");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.SHIPPING, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markGoodsReceived(long ticketId, UserPrincipal actor) {
        if (!IMPORT_ROLES.contains(actor.role()) && !isAdmin(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"SHIPPING".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=SHIPPING");
        }
        tickets.updateFulfillmentStatus(ticketId, "GOODS_RECEIVED");
        // Also advance payment track to AWAITING_FINAL_PAYMENT if deposit was paid
        if ("DEPOSIT_PAID".equals(s.paymentStatus())) {
            tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.GOODS_RECEIVED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto confirmFinalPayment(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"AWAITING_FINAL_PAYMENT".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected paymentStatus=AWAITING_FINAL_PAYMENT");
        }
        tickets.updatePaymentStatus(ticketId, "FULLY_PAID");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.FULLY_PAID, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto cancel(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        String currentStatus = ticket.summary().status();
        if (TicketStatus.CLOSED.equals(currentStatus) || TicketStatus.CANCELLED.equals(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot cancel a closed or already cancelled ticket");
        }
        if (ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CANCELLED, currentStatus, TicketStatus.CANCELLED, null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto editItems(long ticketId, EditItemsRequest request, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        String st = s.status();
        boolean isOwner = actor.id() == s.createdById();

        boolean salesCanEdit = SALES_ROLES.contains(actor.role()) && isOwner
            && Set.of(TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED).contains(st);
        boolean adminCanEdit = isAdmin(actor)
            && Set.of(TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED).contains(st);

        if (!salesCanEdit && !adminCanEdit) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์แก้ไขรายการสินค้าในสถานะนี้");
        }
        tickets.replaceItems(ticketId, request.items());
        tickets.setHasEdits(ticketId, true);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.EDITED, st, st, request.note());
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto comment(long ticketId, CommentRequest request, UserPrincipal actor) {
        if (!tickets.existsById(ticketId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.COMMENTED, null, null, request.message());
        return requireTicket(ticketId);
    }

    @Transactional
    public CalculatePricesResult calculatePrices(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!TicketStatus.PRICE_PROPOSED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำนวณราคาได้เฉพาะ ticket ที่มีสถานะ price_proposed");
        }
        TicketDto ticket = priceCalcService.calculateForTicket(ticketId);
        List<PriceBreakdownItemDto> breakdown = priceCalcService.calculateBreakdown(ticketId);
        return new CalculatePricesResult(ticket, breakdown);
    }

    public record CalculatePricesResult(TicketDto ticket, List<PriceBreakdownItemDto> breakdown) {}

    @Transactional
    public TicketDto overrideItemPrice(long ticketId, long itemId, OverridePriceRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!TicketStatus.PRICE_PROPOSED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "override ราคาได้เฉพาะ ticket ที่มีสถานะ price_proposed");
        }
        boolean itemExists = requireTicket(ticketId).items().stream()
            .anyMatch(it -> it.id() == itemId);
        if (!itemExists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Item not found in this ticket");
        }
        tickets.updateItemManualPrice(itemId, request.manualPrice(), request.reason());
        return requireTicket(ticketId);
    }

    // --- helpers ---

    private TicketDto requireTicket(long id) {
        return tickets.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }

    private TicketSummaryDto loadAndVerifyStatus(long ticketId, String expectedStatus) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!expectedStatus.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status '" + expectedStatus + "' but ticket is '" + s.status() + "'");
        }
        return s;
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private boolean isAdmin(UserPrincipal actor) {
        return "admin".equals(actor.role());
    }
}
