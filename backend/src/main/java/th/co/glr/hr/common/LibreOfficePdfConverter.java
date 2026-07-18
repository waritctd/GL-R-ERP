package th.co.glr.hr.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Converts Office documents (XLS/XLSX) to PDF by shelling out to LibreOffice.
// Requires LibreOffice to be installed:
//   macOS:  brew install --cask libreoffice
//   Ubuntu: apt-get install -y libreoffice-calc
//
// A temporary XLS file is written to the system temp dir, converted, and
// both temp files are deleted when done. The --env:UserInstallation flag
// points LibreOffice's user-profile scratch space to /tmp so this works
// even when running as a service account with no home directory.
public final class LibreOfficePdfConverter {

    private static final List<String> SOFFICE_CANDIDATES = List.of(
        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
        "/opt/homebrew/bin/soffice",
        "/usr/local/bin/soffice",
        "/usr/bin/soffice"
    );

    private LibreOfficePdfConverter() {}

    public static byte[] convert(byte[] xlsBytes) {
        String soffice = findSoffice();
        if (soffice == null) {
            throw new IllegalStateException(
                "LibreOffice not found. Install it: brew install --cask libreoffice  (macOS)" +
                " or  apt-get install -y libreoffice-calc  (Ubuntu/Docker)");
        }
        Path tmpXls = null;
        Path pdfPath = null;
        try {
            tmpXls = Files.createTempFile("glr-doc-", ".xls");
            Files.write(tmpXls, xlsBytes);

            Path outDir = tmpXls.getParent();
            ProcessBuilder pb = new ProcessBuilder(
                soffice,
                "--headless",
                "--nofirststartwizard",
                "--norestore",
                "-env:UserInstallation=file:///tmp/lo-profile",
                "--convert-to", "pdf",
                "--outdir", outDir.toAbsolutePath().toString(),
                tmpXls.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Drain stdout/stderr to prevent blocking, then wait.
            byte[] procOut = proc.getInputStream().readAllBytes();
            boolean done = proc.waitFor(120, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                throw new RuntimeException("LibreOffice timed out after 120 s");
            }
            if (proc.exitValue() != 0) {
                throw new RuntimeException(
                    "LibreOffice exited " + proc.exitValue() + ": " + new String(procOut));
            }

            String xlsName = tmpXls.getFileName().toString();
            String pdfName = xlsName.substring(0, xlsName.lastIndexOf('.')) + ".pdf";
            pdfPath = outDir.resolve(pdfName);
            return Files.readAllBytes(pdfPath);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("XLS→PDF conversion failed: " + e.getMessage(), e);
        } finally {
            silentDelete(tmpXls);
            silentDelete(pdfPath);
        }
    }

    private static String findSoffice() {
        for (String candidate : SOFFICE_CANDIDATES) {
            if (new File(candidate).isFile()) return candidate;
        }
        // Fall back to PATH lookup
        try {
            Process which = new ProcessBuilder("which", "soffice")
                .redirectErrorStream(true).start();
            String path = new String(which.getInputStream().readAllBytes()).trim();
            which.waitFor();
            if (!path.isEmpty() && new File(path).isFile()) return path;
        } catch (Exception ignored) {}
        return null;
    }

    private static void silentDelete(Path p) {
        if (p != null) {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        }
    }
}
