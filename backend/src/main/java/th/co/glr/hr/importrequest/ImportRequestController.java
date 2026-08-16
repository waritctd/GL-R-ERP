package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * ใบขอซื้อ (F-SM-001) download. Import/CEO only — enforced in {@link ImportRequestService}, never
 * here; this class only unwraps the session and shapes the response.
 *
 * <p>Read-only: there is no POST. Generating the form records nothing, so nothing here can change a
 * deal's state — see {@link ImportRequestQueryRepository}'s Javadoc for why the stored aggregate is
 * a separate, later change.
 */
@RestController
@RequestMapping("/api")
public class ImportRequestController {

    private final ImportRequestService service;
    private final SessionContext sessions;

    public ImportRequestController(ImportRequestService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    /** Which brands this deal needs a form for — one F-SM-001 per brand. */
    @GetMapping("/tickets/{ticketId}/import-request/brands")
    Map<String, List<String>> brands(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("brands", service.brands(ticketId, user));
    }

    /** Sheet count for a brand's form, so a client can warn before downloading a 2-page form. */
    @GetMapping("/tickets/{ticketId}/import-request/pages")
    Map<String, Integer> pages(@PathVariable long ticketId,
                               @RequestParam String brand,
                               @RequestParam(required = false) String requiredBy,
                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("pageCount", service.pageCount(ticketId, brand, requiredBy, user));
    }

    /**
     * The form itself.
     *
     * @param ref        "ReF. No." printed on the dotted rule. Optional — the business writes it by
     *                   hand today, and this build mints nothing (no sequence, no uniqueness).
     * @param requiredBy "กำหนดวันที่ต้องการของ", free text.
     */
    @GetMapping(value = "/tickets/{ticketId}/import-request", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> download(@PathVariable long ticketId,
                                    @RequestParam String brand,
                                    @RequestParam(required = false) String ref,
                                    @RequestParam(required = false) String requiredBy,
                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        byte[] pdf = service.render(ticketId, brand, ref, requiredBy, user);
        // Brand and ref reach the filename, and both are caller-supplied, so the name is built with
        // ContentDisposition (which RFC-5987 encodes it) rather than string-concatenated into the
        // header — a brand containing a quote or newline would otherwise let a caller inject header
        // content. Non-ASCII survives via filename*, which is why the Thai-capable UTF-8 charset is
        // set explicitly.
        String name = "IR-" + (ref == null || ref.isBlank() ? "draft" : ref.strip())
            + "-" + brand.strip() + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }
}
