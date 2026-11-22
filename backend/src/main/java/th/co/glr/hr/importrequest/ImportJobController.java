package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.importrequest.ImportJobDtos.BackfillRunSummary;
import th.co.glr.hr.importrequest.ImportJobDtos.CreateImportJobRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.ImportJobDto;
import th.co.glr.hr.importrequest.ImportJobDtos.ItemRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.UpdateImportJobRequest;

/** งานนำเข้า: import and the CEO create and correct import requests that have no sales job. Rules live in the service. */
@RestController
@RequestMapping("/api")
public class ImportJobController {

    private final ImportJobService jobs;
    private final SessionContext sessions;

    public ImportJobController(ImportJobService jobs, SessionContext sessions) {
        this.jobs = jobs;
        this.sessions = sessions;
    }

    @PostMapping("/import-jobs")
    ImportJobDto create(@RequestBody CreateImportJobRequest request, HttpSession session) {
        return jobs.create(request, sessions.requireUser(session));
    }

    @GetMapping("/import-jobs/rep-options")
    java.util.Map<String, java.util.List<ImportJobDtos.RepOption>> repOptions(HttpSession session) {
        return java.util.Map.of("reps", jobs.repOptions(sessions.requireUser(session)));
    }

    @GetMapping("/import-jobs/{id}")
    ImportJobDto get(@PathVariable long id, HttpSession session) {
        return jobs.get(id, sessions.requireUser(session));
    }

    @PatchMapping("/import-jobs/{id}")
    ImportJobDto update(@PathVariable long id, @RequestBody UpdateImportJobRequest request, HttpSession session) {
        return jobs.update(id, request, sessions.requireUser(session));
    }

    @PostMapping("/import-jobs/{id}/items")
    ImportJobDto addItem(@PathVariable long id, @RequestBody ItemRequest request, HttpSession session) {
        return jobs.addItem(id, request, sessions.requireUser(session));
    }

    @PatchMapping("/import-jobs/{id}/items/{itemId}")
    ImportJobDto updateItem(@PathVariable long id, @PathVariable long itemId, @RequestBody ItemRequest request, HttpSession session) {
        return jobs.updateItem(id, itemId, request, sessions.requireUser(session));
    }

    @DeleteMapping("/import-jobs/{id}/items/{itemId}")
    ImportJobDto removeItem(@PathVariable long id, @PathVariable long itemId, HttpSession session) {
        return jobs.removeItem(id, itemId, sessions.requireUser(session));
    }

    /** 204 until a backfill has run. */
    @GetMapping("/import-sheet/backfill-runs/latest")
    ResponseEntity<BackfillRunSummary> latestRun(HttpSession session) {
        return jobs.latestRun(sessions.requireUser(session)).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
