package th.co.glr.hr.salesboard.pipelineimport;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.salesboard.pipelineimport.PipelineImportDtos.PipelineImportResult;

/**
 * The one-time pipeline import (spec 2026-11-15 §7.9). sales_manager / ceo only — the gate lives in
 * {@link PipelineImportService}. {@code dryRun} defaults to TRUE: a request that forgets the parameter can only ever
 * preview, never write.
 */
@RestController
@RequestMapping("/api/sales/pipeline-imports")
public class PipelineImportController {
    static final MediaType XLSX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PipelineImportService service;
    private final SessionContext sessions;

    public PipelineImportController(PipelineImportService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PipelineImportResult upload(@RequestParam("file") MultipartFile file,
                                @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                HttpSession session) {
        UserPrincipal actor = sessions.requireUser(session);
        return dryRun ? service.dryRun(file, actor) : service.commit(file, actor);
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(HttpSession session) {
        byte[] bytes = service.template(sessions.requireUser(session));
        return ResponseEntity.ok()
            .contentType(XLSX)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("pipeline-template.xlsx").build().toString())
            .body(bytes);
    }
}
