package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;

/**
 * The gate every Chromium-dependent test stands behind. Locally it is the repo's usual
 * {@code Assumptions.assumeTrue} idiom — skip, never fail, when the tool is absent. In CI
 * ({@code REQUIRE_CHROMIUM=1}, set by {@code .github/workflows/backend-ci.yml}) a missing browser
 * FAILS the test instead: a fidelity gate that silently skips is a gate that goes green by
 * absence, which is exactly the thing this repo has been bitten by before (CLAUDE.md, "a test can
 * pass because the interesting state was never built").
 */
public final class ChromiumTestGate {
    private ChromiumTestGate() {}

    public static boolean required() {
        String v = System.getenv("REQUIRE_CHROMIUM");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    /** Chromium must be launchable: skip locally, fail under {@code REQUIRE_CHROMIUM=1}. */
    public static void requireOrSkip() {
        boolean available = ChromiumPdfPrinter.isAvailable();
        if (required()) {
            assertThat(available)
                .as("REQUIRE_CHROMIUM is set but no headless Chromium could be launched (CHROMIUM_PATH=%s) — "
                    + "the Chromium PDF gate must RUN here, not skip", System.getenv("CHROMIUM_PATH"))
                .isTrue();
        } else {
            Assumptions.assumeTrue(available, "Chromium not available locally");
        }
    }

    /** Both engines must be present (the fidelity gate compares them): skip locally when either
     * is missing, fail under {@code REQUIRE_CHROMIUM=1} — LibreOffice's absence would let the
     * gate go green by absence just as surely as Chromium's. */
    public static void requireBothOrSkip() {
        boolean libreOffice = LibreOfficePdfConverter.isAvailable();
        if (required()) {
            assertThat(libreOffice)
                .as("REQUIRE_CHROMIUM is set but LibreOffice (soffice) is not installed — the HTML/XLS fidelity "
                    + "gate needs both engines and must RUN here, not skip")
                .isTrue();
        } else {
            Assumptions.assumeTrue(libreOffice, "LibreOffice (soffice) not installed");
        }
        requireOrSkip();
    }
}
