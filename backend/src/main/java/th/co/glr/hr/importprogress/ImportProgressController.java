package th.co.glr.hr.importprogress;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importprogress.ImportProgressDtos.FactoryImportProgressDto;
import th.co.glr.hr.importprogress.ImportProgressDtos.OrderEmailTemplateDto;
import th.co.glr.hr.importprogress.ImportProgressRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importprogress.ImportProgressRequests.OrderEmailTemplateRequest;
import th.co.glr.hr.importprogress.ImportProgressRequests.UpdateFactoryImportRequest;

/**
 * Per-factory import-progress endpoints. Reads are open to sales as well as import/ceo (the data is
 * price-free); writes are import/ceo. The role gate lives in {@link ImportProgressService}, not
 * here — this controller only authenticates.
 */
@RestController
@RequestMapping("/api")
public class ImportProgressController {
    private final ImportProgressService service;
    private final SessionContext sessions;

    public ImportProgressController(ImportProgressService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/import-progress")
    Map<String, List<FactoryImportProgressDto>> listAll(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.listAll(user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/import-progress")
    Map<String, List<FactoryImportProgressDto>> list(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.listForPricingRequest(pricingRequestId, user));
    }

    /** Import/ceo: seed a row per factory of the deal (idempotent) and return the tracker. */
    @PostMapping("/pricing-requests/{pricingRequestId}/import-progress")
    Map<String, List<FactoryImportProgressDto>> ensure(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.ensureAndList(pricingRequestId, user));
    }

    @GetMapping("/tickets/{ticketId}/import-progress")
    Map<String, List<FactoryImportProgressDto>> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.listForTicket(ticketId, user));
    }

    @PostMapping("/factory-import-progress/{id}/advance-step")
    Map<String, FactoryImportProgressDto> advance(
        @PathVariable long id,
        @Valid @RequestBody AdvanceImportStepRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("row", service.advanceStep(id, request, user));
    }

    @PatchMapping("/factory-import-progress/{id}")
    Map<String, FactoryImportProgressDto> update(
        @PathVariable long id,
        @RequestBody UpdateFactoryImportRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("row", service.updateEtaNote(id, request, user));
    }

    @PostMapping("/pricing-requests/{pricingRequestId}/import-order-email")
    Map<String, OrderEmailTemplateDto> orderEmail(
        @PathVariable long pricingRequestId,
        @Valid @RequestBody OrderEmailTemplateRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("template", service.orderEmailTemplate(pricingRequestId, request.factoryName(), user));
    }
}
