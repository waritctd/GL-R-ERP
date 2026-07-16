package th.co.glr.hr.catalog.importer;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/**
 * C2/C3/C4 import endpoints:
 *   GET  /api/price-import/factories               — list factories with profiles
 *   GET  /api/price-import/versions?factoryId=     — list versions for factory
 *   GET  /api/price-import/profile/{factoryId}     — get raw profile JSON
 *   PUT  /api/price-import/profile/{factoryId}     — update profile JSON (saved to DB)
 *   POST /api/price-import/upload                  — parse file → staging → UploadReport
 *   POST /api/price-import/validate/{versionId}    — mark duplicates + invalid rows
 *   GET  /api/price-import/staging/{versionId}     — staging report + diff
 *   POST /api/price-import/commit/{versionId}      — staging → product_prices, ACTIVE
 */
@RestController
@RequestMapping("/api/price-import")
public class PriceImportController {

    private final PriceImportService svc;
    private final SessionContext sessions;

    public PriceImportController(PriceImportService svc, SessionContext sessions) {
        this.svc      = svc;
        this.sessions = sessions;
    }

    private UserPrincipal requireImporter(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "ceo", "import");
        return user;
    }

    @GetMapping("/factories")
    List<Map<String, Object>> factories(HttpSession session) {
        requireImporter(session);
        return svc.listFactories();
    }

    @PostMapping("/factories")
    Map<String, Object> createFactory(@RequestBody Map<String, String> body, HttpSession session) {
        requireImporter(session);
        String name = body.get("name");
        if (name == null || name.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ชื่อโรงงานห้ามว่าง");
        return svc.createFactory(name, body.get("country"), body.get("defaultCurrency"));
    }

    @PostMapping(value = "/upload-commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PriceImportService.UploadCommitResult uploadAndCommit(
        @RequestParam("factoryId") long factoryId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "label", required = false) String label,
        HttpSession session
    ) {
        UserPrincipal user = requireImporter(session);
        if (file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        String effectiveLabel = (label != null && !label.isBlank()) ? label : file.getOriginalFilename();
        try {
            return svc.uploadAndCommit(factoryId, file.getOriginalFilename(),
                file.getInputStream(), effectiveLabel, user.id());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "อ่านไฟล์ไม่ได้: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PriceImportService.UploadReport upload(
        @RequestParam("factoryId") long factoryId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "label", required = false) String label,
        HttpSession session
    ) {
        UserPrincipal user = requireImporter(session);
        if (file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");

        String effectiveLabel = (label != null && !label.isBlank())
            ? label
            : file.getOriginalFilename();

        try {
            return svc.uploadAndStage(
                factoryId,
                file.getOriginalFilename(),
                file.getInputStream(),
                effectiveLabel,
                user.id()
            );
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "อ่านไฟล์ไม่ได้: " + e.getMessage());
        }
    }

    @GetMapping(value = "/profile/{factoryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    String profile(@PathVariable long factoryId, HttpSession session) {
        requireImporter(session);
        return svc.getRawProfile(factoryId);
    }

    @PutMapping(value = "/profile/{factoryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> updateProfile(
        @PathVariable long factoryId,
        @RequestBody String configJson,
        HttpSession session
    ) {
        requireImporter(session);
        svc.updateProfile(factoryId, configJson);
        return Map.of("status", "updated", "factoryId", String.valueOf(factoryId));
    }

    @GetMapping("/versions")
    List<Map<String, Object>> versions(
        @RequestParam long factoryId,
        HttpSession session
    ) {
        requireImporter(session);
        return svc.listVersions(factoryId);
    }

    @PostMapping("/validate/{versionId}")
    Map<String, String> validate(@PathVariable long versionId, HttpSession session) {
        requireImporter(session);
        svc.validate(versionId);
        return Map.of("status", "validated", "versionId", String.valueOf(versionId));
    }

    @GetMapping("/staging/{versionId}")
    PriceImportService.StagingReport stagingReport(
        @PathVariable long versionId,
        HttpSession session
    ) {
        requireImporter(session);
        return svc.getStagingReport(versionId);
    }

    @PostMapping("/commit/{versionId}")
    PriceImportService.CommitResult commit(
        @PathVariable long versionId,
        HttpSession session
    ) {
        UserPrincipal user = requireImporter(session);
        return svc.commit(versionId, user.id());
    }
}
