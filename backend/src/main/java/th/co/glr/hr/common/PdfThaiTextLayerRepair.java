package th.co.glr.hr.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Makes Thai text copied from a LibreOffice PDF paste correctly in every reader (sales request #70).
 *
 * LibreOffice labels a glyph in /ToUnicode with the whole cluster it first appeared in (the glyph for ผ becomes "ผู้")
 * and puts the right text in /ActualText spans. A reader that ignores /ActualText pastes ผม as ผู้ม. Those spans pair
 * each glyph with its character, so this rebuilds /ToUnicode from them. Page content, and so the drawing and the
 * /ActualText itself, is not touched.
 *
 * Sara am is the one lossy case: it is drawn as nikhahit + aa, so a reader that ignores /ActualText gets those two
 * characters. They look identical to ำ but a search for ำ will not match them.
 *
 * A customer document must never lose text here: unless every page decodes to the same text through the new map as
 * through /ActualText, the original bytes are returned. */
public final class PdfThaiTextLayerRepair {
    private PdfThaiTextLayerRepair() {}

    private static final Logger log = LoggerFactory.getLogger(PdfThaiTextLayerRepair.class);

    private static final COSName ACTUAL_TEXT = COSName.getPDFName("ActualText");
    private static final int BFCHAR_BLOCK_LIMIT = 100;

    private record Glyph(COSDictionary font, int code) {}

    /** Glyphs shown together; actualText is null outside a span. */
    private record Run(String actualText, List<Glyph> glyphs) {}

    private static final class UnsafeToRepair extends Exception {
        UnsafeToRepair(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The repaired PDF, or the very same array when there is nothing to repair or it cannot be proven safe. */
    public static byte[] repair(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.isEncrypted()) return pdf;
            List<List<Run>> pages = new ArrayList<>();
            for (PDPage page : doc.getPages()) pages.add(readRuns(page));

            Map<COSDictionary, Map<Integer, String>> current = new IdentityHashMap<>();
            for (List<Run> page : pages) {
                for (Run run : page) {
                    for (Glyph glyph : run.glyphs()) {
                        if (!current.containsKey(glyph.font())) current.put(glyph.font(), readToUnicode(glyph.font()));
                    }
                }
            }

            Map<COSDictionary, Map<Integer, String>> repaired = rebuild(pages, current);
            if (repaired.isEmpty()) return pdf;
            for (List<Run> page : pages) requireSameText(page, current, repaired);

            for (Map.Entry<COSDictionary, Map<Integer, String>> font : repaired.entrySet()) {
                writeToUnicode(doc, font.getKey(), font.getValue());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length);
            doc.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (UnsafeToRepair e) {
            log.warn("PDF text layer left as generated: {}", e.getMessage());
            return pdf;
        } catch (IOException | RuntimeException e) {
            log.warn("PDF text layer left as generated: {}", e.toString());
            return pdf;
        }
    }

    private static List<Run> readRuns(PDPage page) throws IOException, UnsafeToRepair {
        if (page.getResources() == null) return List.of();
        COSDictionary fonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
        List<Run> runs = new ArrayList<>();
        // One entry per open marked-content section, so an EMC closes the section it belongs to.
        Deque<Boolean> sections = new ArrayDeque<>();
        String spanText = null;
        List<Glyph> spanGlyphs = null;
        COSDictionary font = null;

        List<Object> tokens = new PDFStreamParser(page).parse();
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator operator)) continue;
            switch (operator.getName()) {
                case "Tf" -> {
                    if (fonts == null || !(tokens.get(i - 2) instanceof COSName name)
                        || fonts.getCOSDictionary(name) == null) {
                        throw new UnsafeToRepair("text uses a font the page does not declare");
                    }
                    font = fonts.getCOSDictionary(name);
                }
                case "BMC" -> sections.push(false);
                case "BDC" -> {
                    String text = tokens.get(i - 1) instanceof COSDictionary properties
                        && properties.getDictionaryObject(ACTUAL_TEXT) instanceof COSString actual
                        ? actual.getString() : null;
                    if (text != null && spanText != null) throw new UnsafeToRepair("nested /ActualText");
                    sections.push(text != null);
                    if (text != null) {
                        spanText = text;
                        spanGlyphs = new ArrayList<>();
                    }
                }
                case "EMC" -> {
                    if (sections.isEmpty()) throw new UnsafeToRepair("unbalanced marked content");
                    if (sections.pop()) {
                        runs.add(new Run(spanText, spanGlyphs));
                        spanText = null;
                        spanGlyphs = null;
                    }
                }
                case "Tj", "'", "\"" -> addGlyphs(tokens.get(i - 1), font, spanGlyphs, runs);
                case "TJ" -> {
                    if (tokens.get(i - 1) instanceof COSArray items) {
                        for (COSBase item : items) addGlyphs(item, font, spanGlyphs, runs);
                    }
                }
                default -> { /* not text */ }
            }
        }
        if (spanText != null) throw new UnsafeToRepair("unclosed /ActualText");
        return runs;
    }

    private static void addGlyphs(Object operand, COSDictionary font, List<Glyph> spanGlyphs, List<Run> runs)
        throws UnsafeToRepair {
        if (!(operand instanceof COSString string)) return;
        if (font == null) throw new UnsafeToRepair("text shown before a font is set");
        if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
            throw new UnsafeToRepair("composite font");
        }
        List<Glyph> glyphs = spanGlyphs != null ? spanGlyphs : new ArrayList<>();
        for (byte code : string.getBytes()) glyphs.add(new Glyph(font, code & 0xff));
        if (spanGlyphs == null) runs.add(new Run(null, glyphs));
    }

    private static Map<Integer, String> readToUnicode(COSDictionary font) throws IOException, UnsafeToRepair {
        if (!(font.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream)) {
            throw new UnsafeToRepair("font has no /ToUnicode");
        }
        CMap cmap;
        try (InputStream in = stream.createInputStream()) {
            cmap = new CMapParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
        }
        Map<Integer, String> labels = new TreeMap<>();
        for (int code = 0; code <= 0xff; code++) {
            String label = cmap.toUnicode(code);
            if (label != null) labels.put(code, label);
        }
        return labels;
    }

    /** The fonts whose map changes, each with its complete new map. A glyph with no span keeps its label, because a
     * multi-character label outside any span is a real ligature. */
    private static Map<COSDictionary, Map<Integer, String>> rebuild(
        List<List<Run>> pages, Map<COSDictionary, Map<Integer, String>> current) throws UnsafeToRepair {
        Map<COSDictionary, Map<Integer, String>> learned = new IdentityHashMap<>();
        for (List<Run> page : pages) {
            for (Run run : page) {
                if (run.actualText() == null) continue;
                String text = perGlyphText(run);
                if (text == null) continue;
                for (int i = 0; i < text.length(); i++) {
                    Glyph glyph = run.glyphs().get(i);
                    String character = String.valueOf(text.charAt(i));
                    String earlier = learned.computeIfAbsent(glyph.font(), f -> new TreeMap<>())
                        .putIfAbsent(glyph.code(), character);
                    if (earlier != null && !earlier.equals(character)) {
                        throw new UnsafeToRepair("one glyph stands for two characters");
                    }
                }
            }
        }

        Map<COSDictionary, Map<Integer, String>> changed = new IdentityHashMap<>();
        for (Map.Entry<COSDictionary, Map<Integer, String>> font : learned.entrySet()) {
            Map<Integer, String> merged = new TreeMap<>(current.get(font.getKey()));
            merged.putAll(font.getValue());
            if (!merged.equals(current.get(font.getKey()))) changed.put(font.getKey(), merged);
        }
        return changed;
    }

    /** The span's text with exactly one char per glyph, or null when they cannot be paired. */
    private static String perGlyphText(Run run) {
        int glyphs = run.glyphs().size();
        if (run.actualText().length() == glyphs) return run.actualText();
        String drawn = drawnOrder(run.actualText());
        return drawn.length() == glyphs ? drawn : null;
    }

    /** Sara am as the shaper draws it: nikhahit, then any tone mark, then aa. */
    private static String drawnOrder(String text) {
        return text.replaceAll("([\\u0E48-\\u0E4B])\\u0E33", "ํ$1า").replace("ำ", "ํา");
    }

    private static void requireSameText(List<Run> page, Map<COSDictionary, Map<Integer, String>> current,
                                         Map<COSDictionary, Map<Integer, String>> repaired) throws UnsafeToRepair {
        StringBuilder expected = new StringBuilder();
        StringBuilder produced = new StringBuilder();
        for (Run run : page) {
            if (run.actualText() != null) {
                expected.append(run.actualText());
            } else {
                appendDecoded(expected, run.glyphs(), current, current);
            }
            appendDecoded(produced, run.glyphs(), repaired, current);
        }
        if (!drawnOrder(expected.toString()).equals(drawnOrder(produced.toString()))) {
            throw new UnsafeToRepair("the repaired map would change the page's text");
        }
    }

    private static void appendDecoded(StringBuilder text, List<Glyph> glyphs,
                                      Map<COSDictionary, Map<Integer, String>> maps,
                                      Map<COSDictionary, Map<Integer, String>> fallback) {
        for (Glyph glyph : glyphs) {
            Map<Integer, String> map = maps.containsKey(glyph.font()) ? maps.get(glyph.font()) : fallback.get(glyph.font());
            String label = map.get(glyph.code());
            if (label != null) text.append(label);
        }
    }

    private static void writeToUnicode(PDDocument doc, COSDictionary font, Map<Integer, String> labels)
        throws IOException {
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
            + "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(labels.entrySet());
        for (int start = 0; start < entries.size(); start += BFCHAR_BLOCK_LIMIT) {
            int end = Math.min(start + BFCHAR_BLOCK_LIMIT, entries.size());
            cmap.append(end - start).append(" beginbfchar\n");
            for (Map.Entry<Integer, String> entry : entries.subList(start, end)) {
                cmap.append(String.format("<%02X> <%s>%n", entry.getKey(), utf16Hex(entry.getValue())));
            }
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");

        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
        }
        font.setItem(COSName.TO_UNICODE, stream);
    }

    private static String utf16Hex(String text) {
        StringBuilder hex = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_16BE)) hex.append(String.format("%02X", b & 0xff));
        return hex.toString();
    }
}
