package th.co.glr.hr.salesboard.receipts;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.DealOption;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ImportSummary;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.LatestImport;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ManagerView;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ResolveRequest;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.UnmatchedRowDto;

/**
 * /api/sales/receipt-imports — the Express receipts upload and its unmatched queue: ACCOUNT ONLY, except the
 * read-only /manager-view (sales_manager, ceo). Every gate lives in {@link ReceiptImportService}.
 */
@RestController
@RequestMapping("/api/sales/receipt-imports")
public class ReceiptImportController {
    private final ReceiptImportService service;
    private final SessionContext sessions;

    public ReceiptImportController(ReceiptImportService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportSummary upload(@RequestParam("file") MultipartFile file, HttpSession session) throws IOException {
        return service.upload(file.getOriginalFilename(), file.getBytes(), sessions.requireUser(session));
    }

    @GetMapping("/latest")
    LatestImport latest(HttpSession session) {
        return service.latest(sessions.requireUser(session));
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(HttpSession session) {
        byte[] body = service.template(sessions.requireUser(session));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"express-receipts-template.csv\"")
            .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
            .body(body);
    }

    @GetMapping("/unmatched")
    List<UnmatchedRowDto> unmatched(HttpSession session) {
        return service.unmatched(sessions.requireUser(session));
    }

    @GetMapping("/ignored")
    List<UnmatchedRowDto> ignored(HttpSession session) {
        return service.ignored(sessions.requireUser(session));
    }

    @GetMapping("/manager-view")
    ManagerView managerView(HttpSession session) {
        return service.managerView(sessions.requireUser(session));
    }

    @GetMapping("/deals")
    List<DealOption> deals(@RequestParam(required = false) String q, HttpSession session) {
        return service.searchDeals(q, sessions.requireUser(session));
    }

    @PostMapping("/unmatched/{rowId}/resolve")
    UnmatchedRowDto resolve(@PathVariable long rowId, @RequestBody ResolveRequest request, HttpSession session) {
        return service.resolve(rowId, request, sessions.requireUser(session));
    }

    @PostMapping("/unmatched/{rowId}/ignore")
    UnmatchedRowDto ignore(@PathVariable long rowId, HttpSession session) {
        return service.ignore(rowId, sessions.requireUser(session));
    }

    @PostMapping("/unmatched/{rowId}/unignore")
    UnmatchedRowDto unignore(@PathVariable long rowId, HttpSession session) {
        return service.unignore(rowId, sessions.requireUser(session));
    }

    @PostMapping("/unmatched/{rowId}/flag-manager")
    UnmatchedRowDto flagManager(@PathVariable long rowId, HttpSession session) {
        return service.flagForManager(rowId, sessions.requireUser(session));
    }
}
