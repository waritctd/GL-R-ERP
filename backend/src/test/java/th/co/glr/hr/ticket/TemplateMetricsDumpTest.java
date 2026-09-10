package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * html-fidelity-spec.md §1 — pins {@code quotation_template.metrics.json} to what
 * {@link TemplateMetricsDump} produces from the real template TODAY, so the checked-in JSON the
 * HTML renderer trusts can never silently drift from the XLS file it is supposed to describe.
 *
 * <p>Regenerate deliberately (after a genuine template change) with:
 * {@code ./mvnw -Dtest=TemplateMetricsDumpTest -Dregenerate=true test} — this OVERWRITES the
 * checked-in JSON + picture assets instead of asserting against them.
 */
class TemplateMetricsDumpTest {
    private static final String JSON_RESOURCE = "templates/quotation_template.metrics.json";

    @Test
    void checkedInMetricsJsonMatchesTheTemplate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fresh;
        try (InputStream in = new ClassPathResource(TemplateMetricsDump.TEMPLATE_RESOURCE).getInputStream()) {
            fresh = TemplateMetricsDump.dump(mapper, in);
        }

        if (Boolean.getBoolean("regenerate")) {
            regenerate(mapper, fresh);
            return;
        }

        assertThat(new ClassPathResource(JSON_RESOURCE).exists())
            .as(JSON_RESOURCE + " must be checked in — generate it once with -Dregenerate=true")
            .isTrue();
        ObjectNode checkedIn;
        try (InputStream in = new ClassPathResource(JSON_RESOURCE).getInputStream()) {
            checkedIn = (ObjectNode) mapper.readTree(in);
        }

        assertThat(fresh).as(
                "quotation_template.metrics.json has drifted from quotation_template.xls — "
                    + "regenerate with -Dregenerate=true and review the diff")
            .isEqualTo(checkedIn);
    }

    @Test
    void keyLayoutCellsCarryTheExpectedLabelsAndBorders() throws Exception {
        // Cheap, human-readable sanity check independent of the full-JSON equality above — if
        // this one ever fails, the byte-for-byte JSON pin will fail too, but this pinpoints WHAT
        // moved without needing to read a 400-cell JSON diff.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fresh;
        try (InputStream in = new ClassPathResource(TemplateMetricsDump.TEMPLATE_RESOURCE).getInputStream()) {
            fresh = TemplateMetricsDump.dump(mapper, in);
        }
        assertThat(fresh.get("columns").get("A").get("widthMm").asDouble()).isGreaterThan(0);
        assertThat(fresh.get("columns").get("I").get("widthMm").asDouble()).isGreaterThan(0);
        assertThat(fresh.get("rows").size()).isEqualTo(TemplateMetricsDump.LAST_ROW - TemplateMetricsDump.FIRST_ROW + 1);
        assertThat(fresh.get("mergedRegions").size()).isGreaterThan(0);
        assertThat(fresh.get("pageSetup").get("xlsPathFitToWidthScaleApprox").asDouble()).isEqualTo(0.86);
    }

    private void regenerate(ObjectMapper mapper, ObjectNode fresh) throws Exception {
        Path resourcesDir = findResourcesDir();
        Path jsonOut = resourcesDir.resolve(JSON_RESOURCE);
        try (OutputStream out = Files.newOutputStream(jsonOut)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, fresh);
        }
        // Dump output only — no main code reads these (SheetPlan takes pictures from the live
        // workbook), so they are TEST resources: src/test/resources/static/brand/template.
        Path picturesOut = testResourcesDir().resolve("static/brand/template");
        try (InputStream in = new ClassPathResource(TemplateMetricsDump.TEMPLATE_RESOURCE).getInputStream()) {
            Map<String, byte[]> pics = TemplateMetricsDump.extractPictures(in, picturesOut);
            System.out.println("Regenerated " + jsonOut + " and " + pics.size()
                + " picture(s) under " + picturesOut);
        }
    }

    private Path testResourcesDir() {
        Path candidate = Path.of("src/test/resources").toAbsolutePath();
        if (Files.isDirectory(candidate)) return candidate;
        throw new IllegalStateException("Could not locate src/test/resources from " + Path.of("").toAbsolutePath());
    }

    private Path findResourcesDir() {
        // Tests run with the backend module as the working directory (mvnw -pl backend or a
        // module-root mvnw); src/main/resources is stable relative to that.
        Path candidate = Path.of("src/main/resources").toAbsolutePath();
        if (Files.isDirectory(candidate)) return candidate;
        throw new IllegalStateException("Could not locate src/main/resources from " + Path.of("").toAbsolutePath());
    }
}
