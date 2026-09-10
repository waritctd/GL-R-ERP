package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.QuotationHtmlDocument;
import th.co.glr.hr.ticket.QuotationRenderModel;

/**
 * The review findings on the first cut of {@link ChromiumPdfPrinter}: Playwright objects are
 * thread-confined (a 4-thread probe failed 4/4 with {@code Cannot find object to call
 * __adopt__}), a dead browser handle was reused forever, and one failed launch cached
 * "unavailable" for the JVM's life. Each is pinned here; guarded by {@link ChromiumTestGate}.
 */
class ChromiumPdfPrinterConcurrencyTest {

    @BeforeEach
    void gate() {
        ChromiumTestGate.requireOrSkip();
    }

    /** Six threads render six DIFFERENT documents at once, released by one latch so they really
     * overlap. Every PDF must be valid and carry ITS OWN quotation number — a shared, unguarded
     * Playwright connection either throws or hands one caller another caller's page. */
    @Test
    void sixParallelRendersAllSucceedWithTheirOwnContent() throws Exception {
        int n = 6;
        List<String> numbers = new ArrayList<>();
        List<String> htmls = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String number = "QT-PAR-000" + i;
            numbers.add(number);
            QuotationRenderModel model = ChromiumPdfPrinterTest.model(i, number);
            htmls.add(QuotationHtmlDocument.render(model));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String html = htmls.get(i);
                futures.add(pool.submit((Callable<byte[]>) () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return ChromiumPdfPrinter.print(html);
                }));
            }
            start.countDown();
            for (int i = 0; i < n; i++) {
                byte[] pdf = futures.get(i).get(3, TimeUnit.MINUTES);
                assertThat(pdf).as("render " + (i + 1) + " is a PDF").startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
                try (PDDocument doc = Loader.loadPDF(pdf)) {
                    assertThat(doc.getNumberOfPages()).as("render " + (i + 1) + " page count").isGreaterThanOrEqualTo(1);
                    String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
                    assertThat(text).as("render " + (i + 1) + " carries its own number").contains(numbers.get(i));
                    for (int j = 0; j < n; j++) {
                        if (j != i) assertThat(text).as("render " + (i + 1) + " must not carry render " + (j + 1) + "'s number")
                            .doesNotContain(numbers.get(j));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The browser process dies between two renders (crash, OOM kill): the stale handle must be
     * noticed and a fresh browser launched, not reused. */
    @Test
    void relaunchesWhenTheBrowserHasDied() throws Exception {
        String html = QuotationHtmlDocument.render(ChromiumPdfPrinterTest.model(2, "QT-DEAD-0001"));
        assertThat(ChromiumPdfPrinter.print(html)).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
        long launchesBefore = ChromiumPdfPrinter.launchAttemptsForTest();

        ChromiumPdfPrinter.killBrowserForTest();
        assertThat(ChromiumPdfPrinter.isBrowserConnectedForTest()).as("the handle is dead").isFalse();
        // A death does not flip availability — the next render is expected to recover.
        assertThat(ChromiumPdfPrinter.isAvailable()).isTrue();

        byte[] pdf = ChromiumPdfPrinter.print(html);
        assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc).replaceAll("\\s+", "")).contains("QT-DEAD-0001");
        }
        assertThat(ChromiumPdfPrinter.isBrowserConnectedForTest()).as("relaunched").isTrue();
        assertThat(ChromiumPdfPrinter.launchAttemptsForTest()).as("exactly one relaunch").isEqualTo(launchesBefore + 1);
    }

    /** A launch that fails (no executable) answers "unavailable" for the cooldown WITHOUT
     * relaunching on every call, and is retried once the cooldown has elapsed — it never poisons
     * availability for the rest of the JVM's life. */
    @Test
    void failedLaunchIsRetriedAfterTheCooldown() {
        try {
            ChromiumPdfPrinter.resetForTest();
            ChromiumPdfPrinter.setExecutablePathForTest("/nonexistent/chromium-for-this-test");
            assertThat(ChromiumPdfPrinter.isAvailable()).as("no executable → unavailable").isFalse();
            assertThat(ChromiumPdfPrinter.availableCacheForTest()).isFalse();
            long attempts = ChromiumPdfPrinter.launchAttemptsForTest();

            assertThat(ChromiumPdfPrinter.isAvailable()).as("inside the cooldown").isFalse();
            assertThat(ChromiumPdfPrinter.launchAttemptsForTest()).as("no relaunch inside the cooldown").isEqualTo(attempts);

            // The environment is repaired (the override goes away) and the cooldown elapses.
            ChromiumPdfPrinter.setExecutablePathForTest(null);
            ChromiumPdfPrinter.expireCooldownForTest();
            assertThat(ChromiumPdfPrinter.isAvailable()).as("retried after the cooldown").isTrue();
            assertThat(ChromiumPdfPrinter.launchAttemptsForTest()).isEqualTo(attempts + 1);
            assertThat(ChromiumPdfPrinter.availableCacheForTest()).isTrue();
        } finally {
            // Whatever happened above, leave the printer healthy for the tests that follow.
            ChromiumPdfPrinter.setExecutablePathForTest(null);
            ChromiumPdfPrinter.expireCooldownForTest();
        }
    }
}
