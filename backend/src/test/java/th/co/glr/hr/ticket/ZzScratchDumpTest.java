package th.co.glr.hr.ticket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** SCRATCH — never commit. Writes each golden fixture's XLS and canonical dump to -Dscratch.out. */
class ZzScratchDumpTest {
    @Test
    @EnabledIfSystemProperty(named = "scratch.out", matches = ".+")
    void dump() throws Exception {
        Path out = Path.of(System.getProperty("scratch.out"));
        Files.createDirectories(out);
        QuotationRenderer renderer = new QuotationRenderer();
        for (String fixture : new String[] {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"}) {
            byte[] xls = renderer.toXls(QuotationRendererNoPictureGoldenTest.model(fixture));
            Files.write(out.resolve(fixture + ".xls"), xls);
            Files.writeString(out.resolve(fixture + ".txt"),
                QuotationRendererNoPictureGoldenTest.canonicalDump(xls), StandardCharsets.UTF_8);
        }
    }
}
