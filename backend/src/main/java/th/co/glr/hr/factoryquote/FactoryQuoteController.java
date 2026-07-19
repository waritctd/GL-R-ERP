package th.co.glr.hr.factoryquote;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkNotAvailableRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.UpdateFactoryQuoteDraftRequest;

@RestController
@RequestMapping("/api")
public class FactoryQuoteController {
    private final FactoryQuoteService factoryQuotes;
    private final SessionContext sessions;

    public FactoryQuoteController(FactoryQuoteService factoryQuotes, SessionContext sessions) {
        this.factoryQuotes = factoryQuotes;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{pricingRequestId}/factory-email-drafts")
    Map<String, List<FactoryQuoteDto>> generateDrafts(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", factoryQuotes.generateDrafts(pricingRequestId, user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/factory-quotes")
    Map<String, List<FactoryQuoteDto>> list(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", factoryQuotes.list(pricingRequestId, user));
    }

    @GetMapping("/factory-quotes/{factoryQuoteId}")
    Map<String, FactoryQuoteDto> get(@PathVariable long factoryQuoteId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.get(factoryQuoteId, user));
    }

    @PutMapping("/factory-quotes/{factoryQuoteId}")
    Map<String, FactoryQuoteDto> updateDraft(
        @PathVariable long factoryQuoteId,
        @RequestBody UpdateFactoryQuoteDraftRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.updateDraft(factoryQuoteId, request, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/send")
    Map<String, FactoryQuoteDto> send(
        @PathVariable long factoryQuoteId,
        @RequestBody SendFactoryQuoteRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.send(factoryQuoteId, request, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/receive")
    Map<String, FactoryQuoteDto> receive(
        @PathVariable long factoryQuoteId,
        @Valid @RequestBody ReceiveFactoryQuoteRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.receive(factoryQuoteId, request, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/start-negotiation")
    Map<String, FactoryQuoteDto> startNegotiation(
        @PathVariable long factoryQuoteId,
        @Valid @RequestBody StartNegotiationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.startNegotiation(factoryQuoteId, request, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/mark-ready-for-costing")
    Map<String, FactoryQuoteDto> markReady(@PathVariable long factoryQuoteId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.markReadyForCosting(factoryQuoteId, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/not-available")
    Map<String, FactoryQuoteDto> markNotAvailable(
        @PathVariable long factoryQuoteId,
        @Valid @RequestBody MarkNotAvailableRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryQuote", factoryQuotes.markNotAvailable(factoryQuoteId, request, user));
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/attachments")
    Map<String, FactoryQuoteAttachmentDto> uploadAttachment(
        @PathVariable long factoryQuoteId,
        @RequestParam("file") MultipartFile file,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("attachment", factoryQuotes.uploadAttachment(factoryQuoteId, file, user));
    }

    @GetMapping("/factory-quote-attachments/{attachmentId}/file")
    ResponseEntity<Resource> downloadAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        FactoryQuoteAttachmentDto attachment = factoryQuotes.getAttachment(attachmentId, user);
        String path = factoryQuotes.attachmentFilePath(attachmentId, user);
        Resource resource = new FileSystemResource(path);
        if (!resource.exists()) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Factory quote attachment file not found");
        }
        String mime = attachment.mimeType() != null ? attachment.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(attachment.fileName()).build().toString())
            .body(resource);
    }

    @DeleteMapping("/factory-quote-attachments/{attachmentId}")
    Map<String, Boolean> deleteAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        factoryQuotes.deleteAttachment(attachmentId, user);
        return Map.of("ok", true);
    }
}
