package th.co.glr.hr.customerquotation;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CancelCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateRevisionRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;

/**
 * Endpoints for Step 4 (Customer Quotation Generation and Issuance): create/get/list keyed by
 * pricing request (mirrors {@code PricingDecisionController}'s own split), everything else keyed
 * by the quotation's own id under {@code /api/customer-quotations/...}. Detail-shaped responses
 * are wrapped as {@code {quotation: ...}}, list-shaped as {@code {items: [...]}} — the same
 * envelope convention {@code PricingDecisionController} already uses, so the frontend's
 * {@code apiRequest} unwrapping stays uniform across both.
 */
@RestController
@RequestMapping("/api")
public class CustomerQuotationController {
    private final CustomerQuotationService quotations;
    private final SessionContext sessions;

    public CustomerQuotationController(CustomerQuotationService quotations, SessionContext sessions) {
        this.quotations = quotations;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{pricingRequestId}/quotations")
    ResponseEntity<Map<String, CustomerQuotationDto>> create(
        @PathVariable long pricingRequestId,
        @Valid @RequestBody(required = false) CreateCustomerQuotationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        CreateCustomerQuotationRequest body = request != null
            ? request : new CreateCustomerQuotationRequest(null, null, null, null, null, null);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("quotation", quotations.create(pricingRequestId, body, user)));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/quotations")
    Map<String, List<CustomerQuotationDto>> listForPricingRequest(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", quotations.listForPricingRequest(pricingRequestId, user));
    }

    @GetMapping("/customer-quotations/{id}")
    Map<String, CustomerQuotationDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.get(id, user));
    }

    @PutMapping("/customer-quotations/{id}")
    Map<String, CustomerQuotationDto> update(@PathVariable long id, @Valid @RequestBody UpdateCustomerQuotationRequest request,
                                             HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.update(id, request, user));
    }

    @PostMapping("/customer-quotations/{id}/preview")
    Map<String, CustomerQuotationDto> preview(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.preview(id, user));
    }

    @PostMapping("/customer-quotations/{id}/issue")
    Map<String, CustomerQuotationDto> issue(@PathVariable long id,
                                            @RequestBody(required = false) IssueCustomerQuotationRequest request,
                                            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation",
            quotations.issue(id, request != null ? request : new IssueCustomerQuotationRequest(null), user));
    }

    @PostMapping("/customer-quotations/{id}/cancel")
    Map<String, CustomerQuotationDto> cancel(@PathVariable long id,
                                             @RequestBody(required = false) CancelCustomerQuotationRequest request,
                                             HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation",
            quotations.cancel(id, request != null ? request : new CancelCustomerQuotationRequest(null), user));
    }

    @PostMapping("/customer-quotations/{id}/revisions")
    Map<String, CustomerQuotationDto> createRevision(@PathVariable long id,
                                                      @RequestBody(required = false) CreateRevisionRequest request,
                                                      HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation",
            quotations.createRevision(id, request != null ? request : new CreateRevisionRequest(null, null), user));
    }

    @PostMapping("/customer-quotations/{id}/outcome")
    Map<String, CustomerQuotationDto> recordOutcome(@PathVariable long id,
                                                     @Valid @RequestBody RecordQuotationOutcomeRequest request,
                                                     HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.recordOutcome(id, request, user));
    }

    @GetMapping("/customer-quotations/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable long id, @RequestParam(defaultValue = "pdf") String format,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        if ("xlsx".equalsIgnoreCase(format)) {
            byte[] bytes = quotations.renderXlsx(id, user);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-" + id + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
        }
        if (!"pdf".equalsIgnoreCase(format)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown format '" + format + "'");
        }
        byte[] bytes = quotations.renderPdf(id, user);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotation-" + id + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }
}
