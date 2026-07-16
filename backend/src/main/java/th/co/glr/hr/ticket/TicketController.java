package th.co.glr.hr.ticket;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.factory.FactoryEmailService;
import org.springframework.web.bind.annotation.PutMapping;
import th.co.glr.hr.ticket.TicketResponses.CalculatePricesResponse;
import th.co.glr.hr.ticket.TicketResponses.TicketDetailResponse;
import th.co.glr.hr.ticket.TicketResponses.TicketListResponse;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService ticketService;
    private final FactoryEmailService factoryEmail;
    private final SessionContext sessions;

    public TicketController(TicketService ticketService, FactoryEmailService factoryEmail, SessionContext sessions) {
        this.ticketService = ticketService;
        this.factoryEmail  = factoryEmail;
        this.sessions      = sessions;
    }

    @GetMapping
    TicketListResponse list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        Page<TicketSummaryDto> result = ticketService.listPage(status, user, PageRequest.resolve(page, size));
        return new TicketListResponse(result.items(), result.page(), result.size(), result.total());
    }

    @PostMapping
    TicketDetailResponse create(@Valid @RequestBody CreateTicketRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.create(request, user));
    }

    @GetMapping("/{id}")
    TicketDetailResponse get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.get(id, user));
    }

    @PostMapping("/{id}/submit")
    TicketDetailResponse submit(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.submit(id, user));
    }

    @PostMapping("/{id}/pickup")
    TicketDetailResponse pickup(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.pickup(id, user));
    }

    @PostMapping("/{id}/propose-price")
    TicketDetailResponse proposePrice(
        @PathVariable long id,
        @Valid @RequestBody ProposePriceRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.proposePrice(id, request, user));
    }

    @PostMapping("/{id}/approve")
    TicketDetailResponse approve(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.approve(id, user));
    }

    @PostMapping("/{id}/reject")
    TicketDetailResponse reject(
        @PathVariable long id,
        @Valid @RequestBody RejectRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.reject(id, request, user));
    }

    @PostMapping("/{id}/quotation")
    TicketDetailResponse quotation(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.generateQuotation(id, user));
    }

    @GetMapping("/{id}/quotations/{quotationId}/file")
    ResponseEntity<byte[]> quotationFile(
        @PathVariable long id,
        @PathVariable long quotationId,
        @RequestParam(defaultValue = "xlsx") String format,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        String normalized = format == null ? "xlsx" : format.trim().toLowerCase();
        if ("pdf".equals(normalized)) {
            byte[] bytes = ticketService.getQuotationPdf(id, quotationId, user);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-" + quotationId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
        }
        byte[] bytes = ticketService.getQuotationXlsx(id, quotationId, user);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-" + quotationId + ".xlsx\"")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @PostMapping("/{id}/close")
    TicketDetailResponse close(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.close(id, user));
    }

    @PostMapping("/{id}/cancel")
    TicketDetailResponse cancel(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.cancel(id, user));
    }

    @PostMapping("/{id}/factory-emails/send")
    Map<String, String> sendFactoryEmail(
        @PathVariable long id,
        @Valid @RequestBody SendFactoryEmailRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        ticketService.assertFactoryEmailAllowed(id, user);
        factoryEmail.send(id, request.factory(), request.to(), request.subject(), request.body());
        return Map.of("status", "sent");
    }

    @PostMapping("/{id}/calculate-prices")
    CalculatePricesResponse calculatePrices(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        TicketService.CalculatePricesResult result = ticketService.calculatePrices(id, user);
        return new CalculatePricesResponse(result.ticket(), result.breakdown());
    }

    @PutMapping("/{id}/items/{itemId}/price-override")
    TicketDetailResponse overrideItemPrice(
        @PathVariable long id,
        @PathVariable long itemId,
        @Valid @RequestBody OverridePriceRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.overrideItemPrice(id, itemId, request, user));
    }

    @PatchMapping("/{id}/items")
    TicketDetailResponse editItems(
        @PathVariable long id,
        @Valid @RequestBody EditItemsRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.editItems(id, request, user));
    }

    @PostMapping("/{id}/comments")
    TicketDetailResponse comment(
        @PathVariable long id,
        @Valid @RequestBody CommentRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.comment(id, request, user));
    }

    // ── Dual-track post-quotation endpoints (ข้อ 13) ─────────────────────────

    @PostMapping("/{id}/confirm-customer")
    TicketDetailResponse confirmCustomer(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.confirmCustomer(id, user));
    }

    @PostMapping("/{id}/deposit-paid")
    TicketDetailResponse confirmDepositPaid(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.confirmDepositPaid(id, user));
    }

    @PostMapping("/{id}/import-request")
    TicketDetailResponse issueImportRequest(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.issueImportRequest(id, user));
    }

    @PostMapping("/{id}/ir-sent")
    TicketDetailResponse markIrSent(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.markIrSent(id, user));
    }

    @PostMapping("/{id}/shipping")
    TicketDetailResponse markShipping(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.markShipping(id, user));
    }

    @PostMapping("/{id}/goods-received")
    TicketDetailResponse markGoodsReceived(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.markGoodsReceived(id, user));
    }

    @PostMapping("/{id}/final-payment")
    TicketDetailResponse confirmFinalPayment(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.confirmFinalPayment(id, user));
    }
}
