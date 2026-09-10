package th.co.glr.hr.dealquotation;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.EmployeeSignatureRepository.SignatureImage;

/**
 * Quotation v2 (direct deal quotation, V165) — see {@link EmployeeSignatureService} for the
 * authz rule this controller only delegates to ({@code sessions.requireUser} + pass-through,
 * per this codebase's convention that every authz decision lives in the service).
 */
@RestController
@RequestMapping("/api/employees/{id}/signature")
public class EmployeeSignatureController {
    private final EmployeeSignatureService signatures;
    private final SessionContext sessions;

    public EmployeeSignatureController(EmployeeSignatureService signatures, SessionContext sessions) {
        this.signatures = signatures;
        this.sessions = sessions;
    }

    @PutMapping
    ResponseEntity<Void> upload(@PathVariable("id") long employeeId, @RequestParam("file") MultipartFile file,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        signatures.upload(employeeId, file, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    ResponseEntity<byte[]> get(@PathVariable("id") long employeeId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        SignatureImage image = signatures.get(employeeId, user);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.mimeType()))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(image.image());
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable("id") long employeeId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        signatures.delete(employeeId, user);
        return ResponseEntity.noContent().build();
    }
}
