package th.co.glr.hr.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Sales request #70: text copied from a LibreOffice PDF garbles in readers that ignore /ActualText. PDFBox's own
 * text stripper honours /ActualText and so cannot show the bug; plainReaderText decodes through /ToUnicode alone. */
class PdfThaiTextLayerRepairTest {

    private static final String NIKHAHIT = "ํ";
    private static final String MAI_THO = "้";
    private static final String SARA_AA = "า";

    @Test
    void clusterLabelOnBaseLetter_pastesEachLetterAsItself() throws IOException {
        byte[] pdf = pdf(labels(1, "ผู้", 2, "?", 3, "?", 4, "ม"),
            span("ผู้", 1, 2, 3) + span("ผ", 1) + plain(4));

        assertEquals("ผู้ผม", plainReaderText(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void saraAm_keepsBothDrawnParts() throws IOException {
        byte[] pdf = pdf(labels(1, "จำ", 2, "?", 3, SARA_AA, 4, "น"),
            span("จำ", 1, 2, 3) + plain(4));

        assertEquals("จ" + NIKHAHIT + SARA_AA + "น", plainReaderText(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void saraAmWithTone_followsTheDrawnOrder() throws IOException {
        // The shaper draws น + nikhahit + tone + aa for the stored text น + tone + sara am.
        byte[] pdf = pdf(labels(1, "น้ำ", 2, "?", 3, "?", 4, SARA_AA, 5, "จำ"),
            span("น้ำ", 1, 2, 3, 4) + span("จำ", 5, 2, 4));

        assertEquals("น" + NIKHAHIT + MAI_THO + SARA_AA + "จ" + NIKHAHIT + SARA_AA,
            plainReaderText(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void twoGlyphShapesOfOneTone_bothPasteAsThatTone() throws IOException {
        byte[] pdf = pdf(labels(1, "ค้", 2, "?", 3, "บื้", 4, "?", 5, "?"),
            span("ค้", 1, 2) + span("บื้", 3, 4, 5));

        assertEquals("ค้บื้", plainReaderText(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void realLigature_keepsItsWholeMapping() throws IOException {
        // One glyph really draws ด้ here, so it has no span and its two-character label is correct.
        byte[] pdf = pdf(labels(1, "ด้", 2, "ร", 3, "ผู้", 4, "?", 5, "?"),
            plain(1, 2) + span("ผู้", 3, 4, 5));

        assertEquals("ด้รผู้", plainReaderText(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void actualText_andDrawing_areUntouched() throws IOException {
        byte[] pdf = pdf(labels(1, "ผู้", 2, "?", 3, "?", 4, "ม"),
            span("ผู้", 1, 2, 3) + span("ผ", 1) + plain(4));

        assertArrayEquals(pageContent(pdf), pageContent(PdfThaiTextLayerRepair.repair(pdf)));
    }

    @Test
    void conflictingGlyph_returnsTheOriginalPdf() throws IOException {
        byte[] pdf = pdf(labels(1, "กิ", 2, "?"), span("กิ", 1, 2) + span("ขิ", 1, 2));

        assertSame(pdf, PdfThaiTextLayerRepair.repair(pdf));
    }

    @Test
    void glyphCountMismatch_returnsTheOriginalPdf() throws IOException {
        byte[] pdf = pdf(labels(1, "ผู้", 2, "?", 3, "กิ", 4, "?"),
            span("ผู้", 1, 2) + span("กิ", 3, 4));

        assertSame(pdf, PdfThaiTextLayerRepair.repair(pdf));
    }

    @Test
    void noActualText_returnsTheOriginalPdf() throws IOException {
        byte[] pdf = pdf(labels(1, "ก", 2, "ข"), plain(1, 2));

        assertSame(pdf, PdfThaiTextLayerRepair.repair(pdf));
    }

    @Test
    void compositeFont_returnsTheOriginalPdf() throws IOException {
        byte[] pdf = pdf(labels(1, "ผู้", 2, "?", 3, "?"), span("ผู้", 1, 2, 3), COSName.TYPE0);

        assertSame(pdf, PdfThaiTextLayerRepair.repair(pdf));
    }

    @Test
    void corruptBytes_returnsTheInput() {
        byte[] notAPdf = "not a pdf".getBytes(StandardCharsets.UTF_8);

        assertSame(notAPdf, PdfThaiTextLayerRepair.repair(notAPdf));
    }

    @Test
    void realLibreOffice_plainReaderGetsTheThaiBack() throws IOException {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(), "LibreOffice is not available");
        String text = "ผู้ติดต่อ ผม ที่อยู่ ทีม บริษัท";
        byte[] xls;
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("S");
            sheet.setColumnWidth(0, 20000);
            sheet.createRow(0).createCell(0).setCellValue(text);
            workbook.write(out);
            xls = out.toByteArray();
        }

        String extracted = plainReaderText(LibreOfficePdfConverter.convert(xls));

        assertTrue(extracted.contains(text.replaceAll("\\s", "")), extracted);
    }

    // ── a minimal PDF shaped like LibreOffice's output ────────────────────────────────────────

    private static Map<Integer, String> labels(Object... codeThenLabel) {
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < codeThenLabel.length; i += 2) {
            labels.put((Integer) codeThenLabel[i], (String) codeThenLabel[i + 1]);
        }
        return labels;
    }

    private static String span(String actualText, int... codes) {
        return "/Span<</ActualText<FEFF" + utf16Hex(actualText) + ">>>BDC " + plain(codes) + "EMC\n";
    }

    private static String plain(int... codes) {
        StringBuilder hex = new StringBuilder("<");
        for (int code : codes) hex.append(String.format("%02X", code));
        return hex.append(">Tj\n").toString();
    }

    private static String utf16Hex(String text) {
        StringBuilder hex = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_16BE)) hex.append(String.format("%02X", b & 0xff));
        return hex.toString();
    }

    private static byte[] pdf(Map<Integer, String> labels, String textOps) throws IOException {
        return pdf(labels, textOps, COSName.TYPE1);
    }

    private static byte[] pdf(Map<Integer, String> labels, String textOps, COSName subtype) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, subtype);
            font.setItem(COSName.BASE_FONT, COSName.getPDFName("Helvetica"));
            font.setInt(COSName.FIRST_CHAR, 0);
            font.setInt(COSName.LAST_CHAR, 255);
            COSArray widths = new COSArray();
            for (int i = 0; i < 256; i++) widths.add(COSInteger.get(500));
            font.setItem(COSName.WIDTHS, widths);
            COSStream toUnicode = doc.getDocument().createCOSStream();
            try (OutputStream cmap = toUnicode.createOutputStream()) {
                cmap.write(toUnicodeCMap(labels).getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, toUnicode);

            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            resources.getCOSObject().setItem(COSName.FONT, fonts);
            page.setResources(resources);
            PDStream content = new PDStream(doc);
            try (OutputStream ops = content.createOutputStream()) {
                ops.write(("BT /F1 12 Tf 72 700 Td\n" + textOps + "ET\n").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            doc.addPage(page);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String toUnicodeCMap(Map<Integer, String> labels) {
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
            + "/CMapName /Test def\n/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n"
            + labels.size() + " beginbfchar\n");
        labels.forEach((code, label) ->
            cmap.append(String.format("<%02X> <%s>%n", code, utf16Hex(label))));
        return cmap.append("endbfchar\nendcmap\nend\nend\n").toString();
    }

    private static String plainReaderText(byte[] pdf) throws IOException {
        StringBuilder text = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                COSDictionary fonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
                CMap toUnicode = null;
                List<Object> tokens = new PDFStreamParser(page).parse();
                for (int i = 0; i < tokens.size(); i++) {
                    if (!(tokens.get(i) instanceof Operator operator)) continue;
                    if ("Tf".equals(operator.getName())) {
                        COSDictionary font = fonts.getCOSDictionary((COSName) tokens.get(i - 2));
                        COSStream stream = (COSStream) font.getDictionaryObject(COSName.TO_UNICODE);
                        try (InputStream in = stream.createInputStream()) {
                            toUnicode = new CMapParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
                        }
                    } else if ("Tj".equals(operator.getName())) {
                        appendDecoded(text, (COSString) tokens.get(i - 1), toUnicode);
                    } else if ("TJ".equals(operator.getName())) {
                        for (COSBase item : (COSArray) tokens.get(i - 1)) {
                            if (item instanceof COSString string) appendDecoded(text, string, toUnicode);
                        }
                    }
                }
            }
        }
        return text.toString().replaceAll("\\s", "");
    }

    private static void appendDecoded(StringBuilder text, COSString string, CMap toUnicode) {
        for (byte code : string.getBytes()) {
            String unicode = toUnicode.toUnicode(code & 0xff);
            if (unicode != null) text.append(unicode);
        }
    }

    private static byte[] pageContent(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf); InputStream in = doc.getPage(0).getContents()) {
            return in.readAllBytes();
        }
    }
}
