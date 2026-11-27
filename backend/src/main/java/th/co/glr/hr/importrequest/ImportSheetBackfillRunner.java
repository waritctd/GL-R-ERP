package th.co.glr.hr.importrequest;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import th.co.glr.hr.importrequest.ImportSheetModels.Result;

/**
 * Starts the one time sheet backfill when {@code app.import-sheet.backfill.file} is set, then ends the process. There is no
 * HTTP way in: only someone with access to the server can run it. Set {@code .mode=COMMIT} and
 * {@code .expected-plan-sha256} from a reviewed dry run to write. Delete this class one release after the production run.
 */
@Component
@ConditionalOnProperty(prefix = "app.import-sheet.backfill", name = "file")
@Order(Ordered.HIGHEST_PRECEDENCE)
class ImportSheetBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportSheetBackfillRunner.class);

    private final ImportSheetBackfillService service;
    private final ConfigurableApplicationContext context;
    private final String file;
    private final String choicesFile;
    private final String mode;
    private final Long actorEmployeeId;
    private final String reportDir;
    private final String expectedPlanSha256;

    ImportSheetBackfillRunner(ImportSheetBackfillService service, ConfigurableApplicationContext context,
                              @Value("${app.import-sheet.backfill.file}") String file,
                              @Value("${app.import-sheet.backfill.choices-file:}") String choicesFile,
                              @Value("${app.import-sheet.backfill.mode:DRY_RUN}") String mode,
                              @Value("${app.import-sheet.backfill.actor-employee-id:#{null}}") Long actorEmployeeId,
                              @Value("${app.import-sheet.backfill.report-dir:./import-sheet-report}") String reportDir,
                              @Value("${app.import-sheet.backfill.expected-plan-sha256:}") String expectedPlanSha256) {
        this.service = service;
        this.context = context;
        this.file = file;
        this.choicesFile = choicesFile;
        this.mode = mode;
        this.actorEmployeeId = actorEmployeeId;
        this.reportDir = reportDir;
        this.expectedPlanSha256 = expectedPlanSha256;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            Path choices = choicesFile.isBlank() ? null : Path.of(choicesFile);
            Result r;
            if ("COMMIT".equals(mode)) {
                if (actorEmployeeId == null || expectedPlanSha256.isBlank()) {
                    throw new IllegalArgumentException("COMMIT needs actor-employee-id and expected-plan-sha256");
                }
                r = service.commit(Path.of(file), choices, Path.of(reportDir), actorEmployeeId, expectedPlanSha256);
                code = r.failed().isEmpty() ? 0 : 2;
            } else if ("DRY_RUN".equals(mode)) {
                r = service.dryRun(Path.of(file), choices, Path.of(reportDir));
            } else {
                throw new IllegalArgumentException("mode must be DRY_RUN or COMMIT");
            }
            log.info("Import sheet backfill {}: totals={} planSha256={} failed={} report={}", r.mode(), r.totals(), r.planSha256(),
                r.failed().size(), r.reportPaths().markdown());
        } catch (Exception e) {
            log.error("Import sheet backfill stopped", e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
