package th.co.glr.hr.common;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Whether ImageIO can actually DECODE a byte array claiming to be an image — not just whether it
 * carries the right container magic bytes. A magic-byte sniff (PNG's 8-byte signature, JPEG's
 * {@code FF D8 FF}, as {@code EmployeeSignatureService#sniffImageMimeType} does) proves the
 * container type and nothing else: a 16-bit-depth PNG, an interlaced/APNG, or a CMYK JPEG all
 * pass that sniff and then fail here, silently, at whatever later point assumes a decodable
 * image.
 *
 * <p>{@code org.apache.poi.ss.util.ImageUtils#getImageDimension} — what POI's
 * {@code Picture#getImageDimension()} calls — SWALLOWS this exact failure and returns
 * {@code Dimension(0, 0)} rather than throwing. That is how an undecodable signature image used
 * to reach {@code QuotationRenderer} as a zero-width provisional anchor that LibreOffice's PDF
 * export then silently dropped: the picture "existed" in POI's object model, so nothing logged
 * or threw, and the printed document simply had no signature. Any caller that will hand image
 * bytes to POI, or persist bytes another reader will assume are decodable, should probe with this
 * class FIRST — rejecting or otherwise handling the failure explicitly — rather than relying on
 * {@code ImageUtils}' own dimension check to notice.
 *
 * <p>Used by {@code QuotationRenderer#decodableImageSize} (render time — an undecodable image
 * already in {@code hr.employee_signature} degrades to the text-only approver name) and by
 * {@code EmployeeSignatureService#upload} (upload time — reject before anything bad is ever
 * stored). Both go through the same probe so behaviour can never quietly diverge between them.
 */
public final class ImageDecodability {

    private ImageDecodability() {
    }

    /**
     * Decodes {@code imageBytes}, or returns {@code null} on anything ImageIO rejects — a missing
     * reader (unsupported/corrupt format, {@code ImageIO.read} returns {@code null}) or a reader
     * that throws partway through (e.g. a CMYK JPEG, which some JDKs recognise by header but
     * cannot actually decode).
     */
    public static BufferedImage decode(byte[] imageBytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The decoded natural size, or {@code null} when {@link #decode} would return {@code null}.
     */
    public static Dimension size(byte[] imageBytes) {
        BufferedImage decoded = decode(imageBytes);
        return decoded != null ? new Dimension(decoded.getWidth(), decoded.getHeight()) : null;
    }
}
