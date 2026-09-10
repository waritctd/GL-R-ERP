package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The image's Chromium path is a deploy-time fact, and getting it wrong is silent: the first build
 * of the Dockerfile set CHROMIUM_PATH=/usr/bin/chromium while the package installed
 * /usr/bin/chromium-browser, so nothing failed until a PDF was requested. These pin the two halves
 * of the fix — the candidate list covers both package layouts, and the Dockerfile's value is one
 * of them.
 */
class ChromiumPathResolutionTest {

    @Test
    void candidatesCoverBothDebianAndUbuntuPackageLayouts() {
        assertThat(ChromiumPdfPrinter.SYSTEM_CHROMIUM_CANDIDATES)
            .containsSubsequence("/usr/bin/chromium", "/usr/bin/chromium-browser");
    }

    @Test
    void dockerfileChromiumPathIsOneOfTheCandidates() throws Exception {
        List<String> dockerfile = java.nio.file.Files.readAllLines(java.nio.file.Path.of("Dockerfile"));
        String envLine = dockerfile.stream()
            .filter(line -> line.startsWith("ENV CHROMIUM_PATH="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Dockerfile no longer sets CHROMIUM_PATH"));
        String path = envLine.substring("ENV CHROMIUM_PATH=".length()).trim();
        assertThat(ChromiumPdfPrinter.SYSTEM_CHROMIUM_CANDIDATES)
            .as("Dockerfile's CHROMIUM_PATH must be a path the resolver also knows about")
            .contains(path);
    }
}
