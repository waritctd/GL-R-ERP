package th.co.glr.hr.deliveryrequest;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ConfirmInvoiceRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.CreateRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ReturnRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.RoundResponse;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.RoundsResponse;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.SendRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.TaxInvoiceUpload;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.UpdateRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.VoidInvoiceRequest;
import th.co.glr.hr.ticket.RoundDeliveryRequest;

@RestController
@RequestMapping("/api")
public class DeliveryRequestController {
    private final DeliveryRequestService service;
    private final SessionContext sessions;

    public DeliveryRequestController(DeliveryRequestService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/tickets/{ticketId}/delivery-requests")
    RoundsResponse list(@PathVariable long ticketId, HttpSession session) {
        return service.list(ticketId, sessions.requireUser(session));
    }

    @PostMapping("/tickets/{ticketId}/delivery-requests")
    RoundResponse createDraft(@PathVariable long ticketId, @RequestBody(required = false) CreateRequest request,
                              HttpSession session) {
        return new RoundResponse(service.createDraft(ticketId,
            request == null ? null : request.quotationId(), sessions.requireUser(session)));
    }

    @PatchMapping("/delivery-requests/{rid}")
    RoundResponse update(@PathVariable long rid, @RequestBody UpdateRequest request, HttpSession session) {
        return new RoundResponse(service.update(rid, request, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/files")
    RoundResponse addFile(@PathVariable long rid, @RequestParam("file") MultipartFile file, HttpSession session) {
        return new RoundResponse(service.addFile(rid, file, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/send")
    RoundResponse send(@PathVariable long rid, @RequestBody(required = false) SendRequest request, HttpSession session) {
        return new RoundResponse(service.send(rid, request == null ? null : request.systemDocs(),
            request == null ? null : request.preDeposit(), sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/forward")
    RoundResponse forward(@PathVariable long rid, HttpSession session) {
        return new RoundResponse(service.forward(rid, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/tax-invoice")
    TaxInvoiceUpload attachTaxInvoice(@PathVariable long rid, @RequestParam("file") MultipartFile file, HttpSession session) {
        var actor = sessions.requireUser(session);
        // The PDF is read here, outside any transaction and row lock.
        return service.attachTaxInvoice(rid, file, actor, service.prepareTaxInvoice(file, actor));
    }

    @PostMapping("/delivery-requests/{rid}/confirm-invoice")
    RoundResponse confirmInvoice(@PathVariable long rid, @RequestBody ConfirmInvoiceRequest request, HttpSession session) {
        return new RoundResponse(service.confirmInvoice(rid, request, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/void-invoice")
    RoundResponse voidInvoice(@PathVariable long rid, @RequestBody VoidInvoiceRequest request, HttpSession session) {
        return new RoundResponse(service.voidInvoice(rid, request, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/deliveries")
    RoundResponse recordDelivery(@PathVariable long rid, @jakarta.validation.Valid @RequestBody RoundDeliveryRequest request,
                                 HttpSession session) {
        return new RoundResponse(service.recordDelivery(rid, request, sessions.requireUser(session)));
    }

    @PostMapping("/delivery-requests/{rid}/return")
    RoundResponse sendBack(@PathVariable long rid, @RequestBody ReturnRequest request, HttpSession session) {
        return new RoundResponse(service.sendBack(rid, request, sessions.requireUser(session)));
    }
}
