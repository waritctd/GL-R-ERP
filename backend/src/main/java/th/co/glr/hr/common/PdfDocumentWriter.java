package th.co.glr.hr.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;

// Shared simple-text PDF builder with an embeddable Thai font and automatic pagination.
// Used by document renderers (deposit notices, quotations, payslips) that need
// Thai-correct PDF output without pulling in a full templating engine.
//
// Input safety: PDFBox's showText() throws on control characters (U+000A crashes
// PDType0Font), so every drawing method sanitizes its input — multiline values are
// split on newlines and each line has remaining control characters replaced. text()
// additionally wraps lines that exceed the printable width instead of clipping them
// off the right edge of the page.
public final class PdfDocumentWriter implements AutoCloseable {
    public static final String FONT_REGULAR = "fonts/Sarabun-Regular.ttf";
    public static final String FONT_BOLD = "fonts/Sarabun-Bold.ttf";
    private static final float PAGE_MARGIN = 50f;
    private static final float LINE_FACTOR = 1.5f;

    private final PDDocument pdf;
    private final float pageHeight;
    private final float pageWidth;
    private PDPageContentStream cs;
    private float y;

    public PdfDocumentWriter() throws IOException {
        this.pdf = new PDDocument();
        this.pageHeight = PDRectangle.A4.getHeight();
        this.pageWidth = PDRectangle.A4.getWidth();
        newPage();
    }

    public PDFont loadFont(String resourcePath) throws IOException {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return PDType0Font.load(pdf, in);
        }
    }

    /** Left edge of the printable area. */
    public float left() {
        return PAGE_MARGIN;
    }

    /** Right edge of the printable area. */
    public float right() {
        return pageWidth - PAGE_MARGIN;
    }

    /**
     * Paragraph text at the left margin: splits on newlines, wraps lines wider than
     * the printable area, advances the cursor, and paginates as needed.
     */
    public void text(PDFont font, float size, String value) throws IOException {
        for (String line : splitLines(value)) {
            for (String piece : wrap(font, size, line, right() - left())) {
                ensureRoom(size);
                drawLine(font, size, PAGE_MARGIN, piece);
                y -= size * LINE_FACTOR;
            }
        }
    }

    /** Single line at an explicit x. Does NOT advance the cursor — pair with newLine(). */
    public void textAt(PDFont font, float size, float x, String value) throws IOException {
        drawLine(font, size, x, firstLine(value));
    }

    /** Single line right-aligned so it ends at xRight. Does NOT advance the cursor. */
    public void textRight(PDFont font, float size, float xRight, String value) throws IOException {
        String line = firstLine(value);
        drawLine(font, size, xRight - width(font, size, line), line);
    }

    /** Advance the cursor by one line of the given size. */
    public void newLine(float size) {
        y -= size * LINE_FACTOR;
    }

    /** Rendered width of a (sanitized) single line, in points. */
    public float width(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(sanitizeLine(firstLine(value))) / 1000f * size;
    }

    /** Wrap a value into lines that each fit maxWidth. Newlines force breaks. */
    public List<String> wrap(PDFont font, float size, String value, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        for (String rawLine : splitLines(value)) {
            String line = sanitizeLine(rawLine);
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : line.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(font, size, candidate) <= maxWidth) {
                    current = new StringBuilder(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder();
                }
                // Hard-break a single word that alone exceeds the width (Thai text has
                // no spaces, so long Thai runs land here rather than in the space split).
                String rest = word;
                while (width(font, size, rest) > maxWidth && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(font, size, rest.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                current = new StringBuilder(rest);
            }
            if (!current.isEmpty()) {
                out.add(current.toString());
            }
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** Horizontal rule from x1 to x2 slightly above the current baseline. */
    public void rule(float x1, float x2) throws IOException {
        cs.setLineWidth(0.5f);
        cs.moveTo(x1, y + 4);
        cs.lineTo(x2, y + 4);
        cs.stroke();
    }

    /** Start a new page if fewer than `needed` points remain. Returns true if it did. */
    public boolean ensureRoom(float needed) throws IOException {
        if (y - needed < PAGE_MARGIN) {
            newPage();
            return true;
        }
        return false;
    }

    public void gap(float amount) {
        y -= amount;
    }

    public byte[] toBytes() throws IOException {
        cs.close();
        cs = null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void drawLine(PDFont font, float size, float x, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitizeLine(value));
        cs.endText();
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isEmpty()) {
            return List.of("");
        }
        return List.of(value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static String firstLine(String value) {
        List<String> lines = splitLines(value);
        return lines.get(0);
    }

    // Replace every remaining control character (PDFBox showText() rejects them).
    private static String sanitizeLine(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString();
    }

    private void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        cs = new PDPageContentStream(pdf, page);
        y = pageHeight - PAGE_MARGIN;
    }

    @Override
    public void close() throws IOException {
        pdf.close();
    }
}
