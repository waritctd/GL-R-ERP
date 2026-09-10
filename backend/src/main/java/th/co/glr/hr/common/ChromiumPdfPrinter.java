package th.co.glr.hr.common;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quotation v2 (owner ruling 2026-09-10, "the excel template approach is giving too much
 * problem") — prints a self-contained HTML document (see
 * {@code th.co.glr.hr.dealquotation.QuotationHtmlDocument}) to PDF bytes with headless
 * Chromium, via Playwright for Java. Used ONLY by the direct-deal quotation path
 * ({@code th.co.glr.hr.ticket.QuotationRenderer#toPdf(QuotationRenderModel)}) — the legacy/PCR
 * path and every other document keep going through {@link LibreOfficePdfConverter}.
 *
 * <h2>Threading — one render worker owns the browser</h2>
 * Playwright for Java is NOT thread-safe: every object (the {@link Playwright} connection, the
 * {@link Browser}, contexts, pages) must be used from the thread that created it, and a second
 * thread calling into the same connection corrupts its object registry (a 4-thread probe of the
 * previous version failed 4/4 with {@code Cannot find object to call __adopt__}). So the whole
 * Playwright lifecycle lives on ONE dedicated daemon thread ({@link #WORKER}): {@link #print}
 * and {@link #isAvailable()} submit a task to it and wait, with a timeout. Renders are therefore
 * serialised — which is what a request path wants from a browser process anyway (a fresh
 * {@link BrowserContext} per render still keeps their page state apart). Launching Chromium per
 * request would be far too slow, so the browser lives for the JVM's whole life.
 *
 * <h2>Crash recovery</h2>
 * The browser handle is checked with {@link Browser#isConnected()} before every use and
 * relaunched when the process has died (crash, OOM kill, a render timeout that left the
 * connection in doubt). A FAILED launch does not poison {@link #isAvailable()} forever: the
 * negative answer is cached only for {@link #LAUNCH_RETRY_COOLDOWN_MS}, after which the next
 * caller tries again — a Chromium that was temporarily unlaunchable (disk full, transient
 * {@code /dev/shm} exhaustion) must not turn every later quotation into a 503 until the pod is
 * restarted. Exactly one JVM shutdown hook is registered (in the class initialiser), closing
 * the browser and the worker.
 *
 * <h2>Container launch flags</h2>
 * When a SYSTEM Chromium is used ({@code CHROMIUM_PATH}, or the Dockerfile's
 * {@code /usr/bin/chromium}) it is launched with {@link #SYSTEM_CHROMIUM_ARGS}: the runtime
 * image runs as the unprivileged {@code USER 10001} with no user-namespace sandbox available, so
 * {@code --no-sandbox} is the only way the browser starts at all; Docker's default
 * {@code /dev/shm} is 64 MB, which Chromium's renderer shared memory exhausts on a multi-page
 * document, so {@code --disable-dev-shm-usage} moves it to {@code /tmp}; there is no GPU
 * ({@code --disable-gpu}); and {@code --font-render-hinting=none} keeps glyph shaping
 * platform-independent (the PDF embeds vector glyphs, so this only affects any bitmap paths).
 * Playwright's own bundled Chromium (local dev) is launched with Playwright's defaults.
 *
 * <p>Same availability-guard idiom as {@link LibreOfficePdfConverter#isAvailable()} so tests
 * and callers can gate the Chromium-dependent path the way the repo already gates LibreOffice-
 * and Testcontainers-dependent tests on the tool actually being present — but with the retry
 * cooldown above instead of a permanent cache.
 */
public final class ChromiumPdfPrinter {
    private ChromiumPdfPrinter() {}

    private static final Logger log = LoggerFactory.getLogger(ChromiumPdfPrinter.class);

    /** Playwright's per-operation timeout inside one render (setContent, pdf). */
    static final int RENDER_TIMEOUT_MS = 30_000;
    /** How long {@link #isAvailable()} waits for a launch (the driver unpacks itself on first use). */
    static final int LAUNCH_TIMEOUT_MS = 60_000;
    /** How long {@link #print} waits for its task: queueing behind other renders on the single
     * worker, a possible relaunch, and the render itself. */
    static final int PRINT_WAIT_MS = 2 * RENDER_TIMEOUT_MS + LAUNCH_TIMEOUT_MS;
    /** After a failed launch, {@link #isAvailable()} answers {@code false} without retrying for
     * this long; then the next caller launches again. */
    static final long LAUNCH_RETRY_COOLDOWN_MS = 60_000;
    /** See the class Javadoc, "Container launch flags". */
    static final List<String> SYSTEM_CHROMIUM_ARGS = List.of(
        "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--font-render-hinting=none");

    static final String WORKER_THREAD_NAME = "chromium-pdf-printer";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, WORKER_THREAD_NAME);
        t.setDaemon(true);
        return t;
    });

    static {
        // Exactly one hook, tied to class initialisation — never to a launch, which can happen
        // any number of times over the JVM's life (crash recovery, tests).
        Runtime.getRuntime().addShutdownHook(new Thread(ChromiumPdfPrinter::shutdown, WORKER_THREAD_NAME + "-shutdown"));
    }

    // ── worker-confined state: touched ONLY from tasks running on WORKER ────────────────
    private static Playwright playwright;
    private static Browser browser;

    // ── shared state ────────────────────────────────────────────────────────────────────
    /** {@code TRUE} once a launch has succeeded (stays TRUE across a browser death — the next
     * render relaunches); {@code FALSE} after a failed launch, honoured for the cooldown only;
     * {@code null} = never tried. */
    private static volatile Boolean availableCache;
    private static volatile long lastLaunchFailureAtMs;
    private static final AtomicLong launchAttempts = new AtomicLong();
    /** Test hook: overrides {@link #resolveChromiumPath()} when non-null. */
    private static volatile String executablePathForTest;

    /**
     * Whether headless Chromium can be launched in this environment. A successful launch is
     * remembered for good; a failed one only for {@link #LAUNCH_RETRY_COOLDOWN_MS}, after which
     * it is tried again (see the class Javadoc). Blocks up to {@link #LAUNCH_TIMEOUT_MS} while a
     * launch is in progress.
     */
    public static boolean isAvailable() {
        Boolean cached = availableCache;
        if (Boolean.TRUE.equals(cached)) return true;
        if (Boolean.FALSE.equals(cached)
            && System.currentTimeMillis() - lastLaunchFailureAtMs < LAUNCH_RETRY_COOLDOWN_MS) {
            return false;
        }
        try {
            onWorker(() -> { ensureBrowser(); return null; }, LAUNCH_TIMEOUT_MS);
            return true;
        } catch (RuntimeException e) {
            log.warn("Headless Chromium is not available: {}", e.getMessage());
            return false;
        }
    }

    /** Renders {@code html} (a complete, self-contained document whose pages size themselves — see
     * {@link th.co.glr.hr.common.sheet.SheetHtmlRenderer}) to PDF bytes. Safe to call from any
     * thread; renders are serialised on the worker. */
    public static byte[] print(String html) {
        return onWorker(() -> renderOnWorker(html), PRINT_WAIT_MS);
    }

    /** Closes the shared Browser/Playwright instance (the next call relaunches). Called from the
     * JVM shutdown hook; also callable directly from tests that need a clean slate. */
    public static void close() {
        try {
            onWorker(() -> { discardQuietly(); return null; }, 10_000);
        } catch (RuntimeException e) {
            log.debug("Chromium close: {}", e.getMessage());
        }
    }

    private static void shutdown() {
        close();
        WORKER.shutdownNow();
    }

    // ── worker-side ─────────────────────────────────────────────────────────────────────

    private static byte[] renderOnWorker(String html) {
        ensureBrowser();
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setDefaultTimeout(RENDER_TIMEOUT_MS);
            page.setContent(html, new Page.SetContentOptions().setTimeout(RENDER_TIMEOUT_MS));
            // The document is a SheetHtmlRenderer page stream: every page is an exact
            // 210 x 297 mm box carrying its own margins, centring, and in-page "หน้า X/Y"
            // footer at LibreOffice's position. The printer therefore adds NOTHING of its own —
            // zero margins, no header/footer template — or the page geometry would no longer be
            // the LibreOffice render's.
            return page.pdf(new Page.PdfOptions()
                .setFormat("A4")
                .setPreferCSSPageSize(true)
                .setPrintBackground(true)
                .setDisplayHeaderFooter(false)
                .setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0")));
        } catch (RuntimeException e) {
            // A browser that died mid-render is dropped here so the NEXT render relaunches
            // instead of failing on a dead handle again.
            if (browser != null && !browser.isConnected()) discardQuietly();
            throw new RuntimeException("Chromium PDF render failed: " + e.getMessage(), e);
        }
    }

    /** Launches the browser if there is none, or if the one we have is no longer connected. */
    private static void ensureBrowser() {
        if (browser != null && browser.isConnected()) return;
        if (browser != null) {
            log.warn("Headless Chromium is no longer connected — relaunching");
        }
        discardQuietly();
        launchAttempts.incrementAndGet();
        Playwright pw = null;
        try {
            pw = Playwright.create();
            Browser b = pw.chromium().launch(launchOptions());
            playwright = pw;
            browser = b;
            availableCache = Boolean.TRUE;
        } catch (RuntimeException e) {
            if (pw != null) {
                try { pw.close(); } catch (RuntimeException ignored) { /* best effort */ }
            }
            availableCache = Boolean.FALSE;
            lastLaunchFailureAtMs = System.currentTimeMillis();
            throw e;
        }
    }

    private static BrowserType.LaunchOptions launchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        String chromiumPath = resolveChromiumPath();
        if (chromiumPath != null) {
            options.setExecutablePath(Path.of(chromiumPath));
            options.setArgs(SYSTEM_CHROMIUM_ARGS);
        }
        return options;
    }

    /** Drops the browser and connection, swallowing every error — the state after this is
     * "nothing launched", whatever it was before. */
    private static void discardQuietly() {
        if (browser != null) {
            try { browser.close(); } catch (RuntimeException ignored) { /* already dead */ }
            browser = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (RuntimeException ignored) { /* best effort */ }
            playwright = null;
        }
    }

    /**
     * {@code CHROMIUM_PATH} env var (or {@code /usr/bin/chromium}, the Dockerfile's Debian
     * package path) if it exists on disk — the Docker/CI runtime path, launched with
     * {@link #SYSTEM_CHROMIUM_ARGS}. Otherwise {@code null}, so Playwright falls back to its
     * own bundled Chromium (local dev, after {@code java -cp <classpath>
     * com.microsoft.playwright.CLI install chromium} downloads one — see
     * docs/sales/quotation-v2-plan.md, "Local dev").
     */
    private static String resolveChromiumPath() {
        String override = executablePathForTest;
        if (override != null) return override;
        String envPath = System.getenv("CHROMIUM_PATH");
        if (envPath != null && !envPath.isBlank() && new File(envPath).isFile()) {
            return envPath;
        }
        File dockerDefault = new File("/usr/bin/chromium");
        if (dockerDefault.isFile()) {
            return dockerDefault.getAbsolutePath();
        }
        return null;
    }

    // ── worker plumbing ─────────────────────────────────────────────────────────────────

    private static <T> T onWorker(Callable<T> task, long timeoutMs) {
        Future<T> future;
        try {
            future = WORKER.submit(task);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Chromium PDF printer is shut down", e);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Do NOT interrupt the worker: Playwright's own per-operation timeout unblocks it,
            // and an interrupt mid-protocol would leave the connection in an unknown state.
            // Instead queue a discard, so whatever the worker was doing is thrown away and the
            // next render starts from a fresh launch.
            future.cancel(false);
            try { WORKER.execute(ChromiumPdfPrinter::discardQuietly); } catch (RejectedExecutionException ignored) { /* shutting down */ }
            throw new RuntimeException("Chromium PDF render timed out after " + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            throw new RuntimeException("Interrupted while waiting for the Chromium PDF render", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    // ── test hooks (package-private; see ChromiumPdfPrinterConcurrencyTest) ────────────

    /** Simulates a browser death: closes the browser process but KEEPS the stale handle, exactly
     * the state a crash leaves behind, so the next render must notice and relaunch. */
    static void killBrowserForTest() {
        onWorker(() -> {
            if (browser != null) browser.close();
            return null;
        }, 10_000);
    }

    static boolean isBrowserConnectedForTest() {
        return onWorker(() -> browser != null && browser.isConnected(), 10_000);
    }

    /** Overrides the executable Playwright is pointed at ({@code null} = normal resolution). */
    static void setExecutablePathForTest(String path) {
        executablePathForTest = path;
    }

    /** Forgets every cached answer and drops the browser: the next call starts from scratch. */
    static void resetForTest() {
        close();
        availableCache = null;
        lastLaunchFailureAtMs = 0L;
    }

    /** Makes the retry cooldown after a failed launch count as elapsed. */
    static void expireCooldownForTest() {
        lastLaunchFailureAtMs = 0L;
    }

    static long launchAttemptsForTest() {
        return launchAttempts.get();
    }

    static Boolean availableCacheForTest() {
        return availableCache;
    }
}
