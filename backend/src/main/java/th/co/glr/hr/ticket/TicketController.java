package th.co.glr.hr.ticket;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
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
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;

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

    @GetMapping("/{id}/actions")
    TicketActionsResponse actions(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return ticketService.actions(id, user);
    }

    @GetMapping("/{id}/payments")
    Map<String, List<PaymentReceiptDto>> payments(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", ticketService.listPayments(id, user));
    }

    @PostMapping("/{id}/payments")
    TicketDetailResponse recordPayment(
        @PathVariable long id,
        @Valid @RequestBody RecordPaymentRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.recordPayment(id, request, user));
    }

    @PostMapping("/{id}/billing")
    TicketDetailResponse setBilling(
        @PathVariable long id,
        @Valid @RequestBody BillingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.setBilling(id, request, user));
    }

    @GetMapping("/{id}/deliveries")
    Map<String, List<DeliveryRecordDto>> deliveries(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", ticketService.listDeliveries(id, user));
    }

    @PostMapping("/{id}/reserve-stock")
    TicketDetailResponse reserveStock(
        @PathVariable long id,
        @Valid @RequestBody StockReservationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.reserveStock(id, request, user));
    }

    @PostMapping("/{id}/deliveries")
    TicketDetailResponse recordDelivery(
        @PathVariable long id,
        @Valid @RequestBody RecordDeliveryRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.recordPartialDelivery(id, request, user));
    }

    @PostMapping("/{id}/deliveries/complete")
    TicketDetailResponse completeDelivery(
        @PathVariable long id,
        @RequestBody(required = false) CompleteDeliveryRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.completeDelivery(id, request, user));
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
    TicketDetailResponse quotation(@PathVariable long id,
                                   @Valid @RequestBody GenerateQuotationRequest request,
                                   HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.generateQuotation(id, request, user));
    }

    @PostMapping("/{id}/quotations/{quotationId}/sent")
    TicketDetailResponse markQuotationSent(@PathVariable long id,
                                           @PathVariable long quotationId,
                                           @RequestBody(required = false) NoteRequest request,
                                           HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.markQuotationSent(id, quotationId, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/quotations/{quotationId}/accepted")
    TicketDetailResponse markQuotationAccepted(@PathVariable long id,
                                               @PathVariable long quotationId,
                                               @RequestBody(required = false) NoteRequest request,
                                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.markQuotationAccepted(id, quotationId, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/quotations/{quotationId}/rejected")
    TicketDetailResponse markQuotationRejected(@PathVariable long id,
                                               @PathVariable long quotationId,
                                               @RequestBody(required = false) NoteRequest request,
                                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.markQuotationRejected(id, quotationId, request == null ? null : request.note(), user));
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

    // Three-party close (V55): ฝ่ายบัญชี confirms, then the CEO verifies. The old
    // single-step POST /{id}/close (sales owner, one signature) is gone.
    @PostMapping("/{id}/close/confirm")
    TicketDetailResponse confirmCloseReady(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.confirmCloseReady(id, user));
    }

    @PostMapping("/{id}/close/revoke")
    TicketDetailResponse revokeCloseConfirmation(@PathVariable long id,
                                                 @RequestBody(required = false) NoteRequest body,
                                                 HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.revokeCloseConfirmation(id, body == null ? null : body.note(), user));
    }

    @PostMapping("/{id}/close/verify")
    TicketDetailResponse verifyClose(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.verifyClose(id, user));
    }

    @PostMapping("/{id}/cancel")
    TicketDetailResponse cancel(@PathVariable long id,
                                @Valid @RequestBody CancelRequest request,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.cancel(id, request.reason(), request.note(), user));
    }

    // ── Deal pipeline (V50) ─────────────────────────────────────────────────

    @PostMapping("/{id}/stage")
    TicketDetailResponse updateStage(@PathVariable long id,
                                     @Valid @RequestBody UpdateStageRequest request,
                                     HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.updateStage(id, request.stage(), request.note(), user));
    }

    @PostMapping("/{id}/lost")
    TicketDetailResponse markLost(@PathVariable long id,
                                  @Valid @RequestBody MarkLostRequest request,
                                  HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.markLost(id, request.reason(), request.note(), user));
    }

    @PostMapping("/{id}/reopen")
    TicketDetailResponse reopen(@PathVariable long id,
                                @RequestBody(required = false) ReopenRequest request,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.reopenDeal(id, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/hold")
    TicketDetailResponse hold(@PathVariable long id,
                              @RequestBody(required = false) NoteRequest request,
                              HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.placeOnHold(id, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/dormant")
    TicketDetailResponse dormant(@PathVariable long id,
                                 @RequestBody(required = false) NoteRequest request,
                                 HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.markDormant(id, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/resume")
    TicketDetailResponse resume(@PathVariable long id,
                                @RequestBody(required = false) NoteRequest request,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(
            ticketService.resume(id, request == null ? null : request.note(), user));
    }

    @PostMapping("/{id}/tender-requirement")
    TicketDetailResponse tenderRequirement(@PathVariable long id,
                                           @Valid @RequestBody PolicyValueRequest request,
                                           HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.setTenderRequirement(id, request.value(), user));
    }

    @PostMapping("/{id}/entry-channel")
    TicketDetailResponse entryChannel(@PathVariable long id,
                                      @Valid @RequestBody EntryChannelRequest request,
                                      HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.setEntryChannel(id, request.value(), request.note(), user));
    }

    @PostMapping("/{id}/deposit-policy")
    TicketDetailResponse depositPolicy(@PathVariable long id,
                                       @Valid @RequestBody DepositPolicyRequest request,
                                       HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.waiveDeposit(id, request.policy(), request.reason(), user));
    }

    record UpdateStageRequest(@jakarta.validation.constraints.NotBlank String stage,
                              @jakarta.validation.constraints.Size(max = 2000) String note) {}

    record MarkLostRequest(@jakarta.validation.constraints.NotBlank String reason,
                           @jakarta.validation.constraints.Size(max = 2000) String note) {}

    /** Same shape as MarkLostRequest — cancel now carries a structured reason too (V56). */
    record CancelRequest(@jakarta.validation.constraints.NotBlank String reason,
                         @jakarta.validation.constraints.Size(max = 2000) String note) {}

    record ReopenRequest(@jakarta.validation.constraints.Size(max = 2000) String note) {}

    record NoteRequest(@jakarta.validation.constraints.Size(max = 2000) String note) {}

    record PolicyValueRequest(@jakarta.validation.constraints.NotBlank String value) {}

    record EntryChannelRequest(@jakarta.validation.constraints.NotBlank String value,
                               @jakarta.validation.constraints.Size(max = 2000) String note) {}

    record DepositPolicyRequest(@jakarta.validation.constraints.NotBlank String policy,
                                @jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 2000) String reason) {}

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
