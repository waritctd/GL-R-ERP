package th.co.glr.hr.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;

// Shared simple-text PDF builder with an embeddable Thai font and automatic pagination.
// Used by document renderers (deposit notices, quotations) that need Thai-correct PDF
// output without pulling in a full templating engine.
public final class PdfDocumentWriter implements AutoCloseable {
    public static final String FONT_REGULAR = "fonts/Sarabun-Regular.ttf";
    public static final String FONT_BOLD = "fonts/Sarabun-Bold.ttf";
    private static final float PAGE_MARGIN = 50f;

    private final PDDocument pdf;
    private final float pageHeight;
    private PDPageContentStream cs;
    private float y;

    public PdfDocumentWriter() throws IOException {
        this.pdf = new PDDocument();
        this.pageHeight = PDRectangle.A4.getHeight();
        newPage();
    }

    public PDFont loadFont(String resourcePath) throws IOException {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return PDType0Font.load(pdf, in);
        }
    }

    public void text(PDFont font, float size, String value) throws IOException {
        if (y - size < PAGE_MARGIN) {
            newPage();
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(PAGE_MARGIN, y);
        cs.showText(value != null ? value : "");
        cs.endText();
        y -= size * 1.5f;
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
